/*
 * Copyright 2024 - 2024 the original author or authors.
 */
package io.modelcontextprotocol.server;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.server.transport.WebMvcSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageRequest;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageResult;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.ModelPreferences;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.Root;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;

public class WebMvcSseIntegrationTests {

	private static final int PORT = 8183;

	private static final String MESSAGE_ENDPOINT = "/mcp/message";

	private WebMvcSseServerTransportProvider mcpServerTransportProvider;

	McpClient.SyncSpec clientBuilder;

	@Configuration
	@EnableWebMvc
	static class TestConfig {

		@Bean
		public WebMvcSseServerTransportProvider webMvcSseServerTransportProvider() {
			return new WebMvcSseServerTransportProvider(new ObjectMapper(), MESSAGE_ENDPOINT);
		}

		@Bean
		public RouterFunction<ServerResponse> routerFunction(WebMvcSseServerTransportProvider transportProvider) {
			return transportProvider.getRouterFunction();
		}

	}

	private Tomcat tomcat;

	private AnnotationConfigWebApplicationContext appContext;

	@BeforeEach
	public void before() {

		// Set up Tomcat first
		tomcat = new Tomcat();
		tomcat.setPort(PORT);

		// Set Tomcat base directory to java.io.tmpdir to avoid permission issues
		String baseDir = System.getProperty("java.io.tmpdir");
		tomcat.setBaseDir(baseDir);

		// Use the same directory for document base
		Context context = tomcat.addContext("", baseDir);

		// Create and configure Spring WebMvc context
		appContext = new AnnotationConfigWebApplicationContext();
		appContext.register(TestConfig.class);
		appContext.setServletContext(context.getServletContext());
		appContext.refresh();

		// Get the transport from Spring context
		mcpServerTransportProvider = appContext.getBean(WebMvcSseServerTransportProvider.class);

		// Create DispatcherServlet with our Spring context
		DispatcherServlet dispatcherServlet = new DispatcherServlet(appContext);
		// dispatcherServlet.setThrowExceptionIfNoHandlerFound(true);

		// Add servlet to Tomcat and get the wrapper
		var wrapper = Tomcat.addServlet(context, "dispatcherServlet", dispatcherServlet);
		wrapper.setLoadOnStartup(1);
		wrapper.setAsyncSupported(true);
		context.addServletMappingDecoded("/*", "dispatcherServlet");

		try {
			// Configure and start the connector with async support
			var connector = tomcat.getConnector();
			connector.setAsyncTimeout(3000); // 3 seconds timeout for async requests
			tomcat.start();
			assertThat(tomcat.getServer().getState() == LifecycleState.STARTED);
		}
		catch (Exception e) {
			throw new RuntimeException("Failed to start Tomcat", e);
		}

		this.clientBuilder = McpClient.sync(new HttpClientSseClientTransport("http://localhost:" + PORT));
	}

	@AfterEach
	public void after() {
		if (mcpServerTransportProvider != null) {
			mcpServerTransportProvider.closeGracefully().block();
		}
		if (appContext != null) {
			appContext.close();
		}
		if (tomcat != null) {
			try {
				tomcat.stop();
				tomcat.destroy();
			}
			catch (LifecycleException e) {
				throw new RuntimeException("Failed to stop Tomcat", e);
			}
		}
	}

	// ---------------------------------------
	// Sampling Tests
	// ---------------------------------------
	@Test
	void testCreateMessageWithoutSamplingCapabilities() {

		McpServerFeatures.AsyncToolSpecification tool = new McpServerFeatures.AsyncToolSpecification(
				new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema), (exchange, request) -> {

					exchange.createMessage(mock(McpSchema.CreateMessageRequest.class)).block();

					return Mono.just(mock(CallToolResult.class));
				});

		McpServer.async(mcpServerTransportProvider).serverInfo("test-server", "1.0.0").tools(tool).build();

		// Create client without sampling capabilities
		var client = clientBuilder.clientInfo(new McpSchema.Implementation("Sample " + "client", "0.0.0")).build();

		assertThat(client.initialize()).isNotNull();

		try {
			client.callTool(new McpSchema.CallToolRequest("tool1", Map.of()));
		}
		catch (McpError e) {
			assertThat(e).isInstanceOf(McpError.class)
				.hasMessage("Client must be configured with sampling capabilities");
		}
	}

	@Test
	void testCreateMessageSuccess() throws InterruptedException {

		// Client

		Function<CreateMessageRequest, CreateMessageResult> samplingHandler = request -> {
			assertThat(request.messages()).hasSize(1);
			assertThat(request.messages().get(0).content()).isInstanceOf(McpSchema.TextContent.class);

			return new CreateMessageResult(Role.USER, new McpSchema.TextContent("Test message"), "MockModelName",
					CreateMessageResult.StopReason.STOP_SEQUENCE);
		};

		var mcpClient = clientBuilder.clientInfo(new McpSchema.Implementation("Sample client", "0.0.0"))
			.capabilities(ClientCapabilities.builder().sampling().build())
			.sampling(samplingHandler)
			.build();

		// Server

		CallToolResult callResponse = new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("CALL RESPONSE")),
				null);

		McpServerFeatures.AsyncToolSpecification tool = new McpServerFeatures.AsyncToolSpecification(
				new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema), (exchange, request) -> {

					var craeteMessageRequest = McpSchema.CreateMessageRequest.builder()
						.messages(List.of(new McpSchema.SamplingMessage(McpSchema.Role.USER,
								new McpSchema.TextContent("Test message"))))
						.modelPreferences(ModelPreferences.builder()
							.hints(List.of())
							.costPriority(1.0)
							.speedPriority(1.0)
							.intelligencePriority(1.0)
							.build())
						.build();

					StepVerifier.create(exchange.createMessage(craeteMessageRequest)).consumeNextWith(result -> {
						assertThat(result).isNotNull();
						assertThat(result.role()).isEqualTo(Role.USER);
						assertThat(result.content()).isInstanceOf(McpSchema.TextContent.class);
						assertThat(((McpSchema.TextContent) result.content()).text()).isEqualTo("Test message");
						assertThat(result.model()).isEqualTo("MockModelName");
						assertThat(result.stopReason()).isEqualTo(CreateMessageResult.StopReason.STOP_SEQUENCE);
					}).verifyComplete();

					return Mono.just(callResponse);
				});

		var mcpServer = McpServer.async(mcpServerTransportProvider)
			.serverInfo("test-server", "1.0.0")
			.tools(tool)
			.build();

		InitializeResult initResult = mcpClient.initialize();
		assertThat(initResult).isNotNull();

		CallToolResult response = mcpClient.callTool(new McpSchema.CallToolRequest("tool1", Map.of()));

		assertThat(response).isNotNull();
		assertThat(response).isEqualTo(callResponse);

		mcpClient.close();
		mcpServer.close();
	}

	// ---------------------------------------
	// Roots Tests
	// ---------------------------------------
	@Test
	void testRootsSuccess() {
		List<Root> roots = List.of(new Root("uri1://", "root1"), new Root("uri2://", "root2"));

		AtomicReference<List<Root>> rootsRef = new AtomicReference<>();
		var mcpServer = McpServer.sync(mcpServerTransportProvider)
			.rootsChangeHandler((exchange, rootsUpdate) -> rootsRef.set(rootsUpdate))
			.build();

		var mcpClient = clientBuilder.capabilities(ClientCapabilities.builder().roots(true).build())
			.roots(roots)
			.build();

		InitializeResult initResult = mcpClient.initialize();
		assertThat(initResult).isNotNull();

		assertThat(rootsRef.get()).isNull();

		mcpClient.rootsListChangedNotification();

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			assertThat(rootsRef.get()).containsAll(roots);
		});

		// Remove a root
		mcpClient.removeRoot(roots.get(0).uri());

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			assertThat(rootsRef.get()).containsAll(List.of(roots.get(1)));
		});

		// Add a new root
		var root3 = new Root("uri3://", "root3");
		mcpClient.addRoot(root3);

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			assertThat(rootsRef.get()).containsAll(List.of(roots.get(1), root3));
		});

		mcpClient.close();
		mcpServer.close();
	}

	@Test
	void testRootsWithoutCapability() {

		McpServerFeatures.SyncToolSpecification tool = new McpServerFeatures.SyncToolSpecification(
				new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema), (exchange, request) -> {

					exchange.listRoots(); // try to list roots

					return mock(CallToolResult.class);
				});

		var mcpServer = McpServer.sync(mcpServerTransportProvider).rootsChangeHandler((exchange, rootsUpdate) -> {
		}).tools(tool).build();

		// Create client without roots capability
		// No roots capability
		var mcpClient = clientBuilder.capabilities(ClientCapabilities.builder().build()).build();

		assertThat(mcpClient.initialize()).isNotNull();

		// Attempt to list roots should fail
		try {
			mcpClient.callTool(new McpSchema.CallToolRequest("tool1", Map.of()));
		}
		catch (McpError e) {
			assertThat(e).isInstanceOf(McpError.class).hasMessage("Roots not supported");
		}

		mcpClient.close();
		mcpServer.close();
	}

	@Test
	void testRootsNotifciationWithEmptyRootsList() {
		AtomicReference<List<Root>> rootsRef = new AtomicReference<>();

		var mcpServer = McpServer.sync(mcpServerTransportProvider)
			.rootsChangeHandler((exchange, rootsUpdate) -> rootsRef.set(rootsUpdate))
			.build();

		var mcpClient = clientBuilder.capabilities(ClientCapabilities.builder().roots(true).build())
			.roots(List.of()) // Empty roots list
			.build();

		InitializeResult initResult = mcpClient.initialize();
		assertThat(initResult).isNotNull();

		mcpClient.rootsListChangedNotification();

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			assertThat(rootsRef.get()).isEmpty();
		});

		mcpClient.close();
		mcpServer.close();
	}

	@Test
	void testRootsWithMultipleHandlers() {
		List<Root> roots = List.of(new Root("uri1://", "root1"));

		AtomicReference<List<Root>> rootsRef1 = new AtomicReference<>();
		AtomicReference<List<Root>> rootsRef2 = new AtomicReference<>();

		var mcpServer = McpServer.sync(mcpServerTransportProvider)
			.rootsChangeHandler((exchange, rootsUpdate) -> rootsRef1.set(rootsUpdate))
			.rootsChangeHandler((exchange, rootsUpdate) -> rootsRef2.set(rootsUpdate))
			.build();

		var mcpClient = clientBuilder.capabilities(ClientCapabilities.builder().roots(true).build())
			.roots(roots)
			.build();

		assertThat(mcpClient.initialize()).isNotNull();

		mcpClient.rootsListChangedNotification();

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			assertThat(rootsRef1.get()).containsAll(roots);
			assertThat(rootsRef2.get()).containsAll(roots);
		});

		mcpClient.close();
		mcpServer.close();
	}

	@Test
	void testRootsServerCloseWithActiveSubscription() {
		List<Root> roots = List.of(new Root("uri1://", "root1"));

		AtomicReference<List<Root>> rootsRef = new AtomicReference<>();
		var mcpServer = McpServer.sync(mcpServerTransportProvider)
			.rootsChangeHandler((exchange, rootsUpdate) -> rootsRef.set(rootsUpdate))
			.build();

		var mcpClient = clientBuilder.capabilities(ClientCapabilities.builder().roots(true).build())
			.roots(roots)
			.build();

		InitializeResult initResult = mcpClient.initialize();
		assertThat(initResult).isNotNull();

		mcpClient.rootsListChangedNotification();

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			assertThat(rootsRef.get()).containsAll(roots);
		});

		// Close server while subscription is active
		mcpServer.close();

		// Verify client can handle server closure gracefully
		mcpClient.close();
	}

	// ---------------------------------------
	// Tools Tests
	// ---------------------------------------

	String emptyJsonSchema = """
			{
				"$schema": "http://json-schema.org/draft-07/schema#",
				"type": "object",
				"properties": {}
			}
			""";

	@Test
	void testToolCallSuccess() {

		var callResponse = new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("CALL RESPONSE")), null);
		McpServerFeatures.SyncToolSpecification tool1 = new McpServerFeatures.SyncToolSpecification(
				new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema), (exchange, request) -> {
					// perform a blocking call to a remote service
					String response = RestClient.create()
						.get()
						.uri("https://github.com/modelcontextprotocol/specification/blob/main/README.md")
						.retrieve()
						.body(String.class);
					assertThat(response).isNotBlank();
					return callResponse;
				});

		var mcpServer = McpServer.sync(mcpServerTransportProvider)
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tools(tool1)
			.build();

		var mcpClient = clientBuilder.build();

		InitializeResult initResult = mcpClient.initialize();
		assertThat(initResult).isNotNull();

		assertThat(mcpClient.listTools().tools()).contains(tool1.tool());

		CallToolResult response = mcpClient.callTool(new McpSchema.CallToolRequest("tool1", Map.of()));

		assertThat(response).isNotNull();
		assertThat(response).isEqualTo(callResponse);

		mcpClient.close();
		mcpServer.close();
	}

	@Test
	void testToolListChangeHandlingSuccess() {

		var callResponse = new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("CALL RESPONSE")), null);
		McpServerFeatures.SyncToolSpecification tool1 = new McpServerFeatures.SyncToolSpecification(
				new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema), (exchange, request) -> {
					// perform a blocking call to a remote service
					String response = RestClient.create()
						.get()
						.uri("https://github.com/modelcontextprotocol/specification/blob/main/README.md")
						.retrieve()
						.body(String.class);
					assertThat(response).isNotBlank();
					return callResponse;
				});

		var mcpServer = McpServer.sync(mcpServerTransportProvider)
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tools(tool1)
			.build();

		AtomicReference<List<Tool>> rootsRef = new AtomicReference<>();
		var mcpClient = clientBuilder.toolsChangeConsumer(toolsUpdate -> {
			// perform a blocking call to a remote service
			String response = RestClient.create()
				.get()
				.uri("https://github.com/modelcontextprotocol/specification/blob/main/README.md")
				.retrieve()
				.body(String.class);
			assertThat(response).isNotBlank();
			rootsRef.set(toolsUpdate);
		}).build();

		InitializeResult initResult = mcpClient.initialize();
		assertThat(initResult).isNotNull();

		assertThat(rootsRef.get()).isNull();

		assertThat(mcpClient.listTools().tools()).contains(tool1.tool());

		mcpServer.notifyToolsListChanged();

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			assertThat(rootsRef.get()).containsAll(List.of(tool1.tool()));
		});

		// Remove a tool
		mcpServer.removeTool("tool1");

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			assertThat(rootsRef.get()).isEmpty();
		});

		// Add a new tool
		McpServerFeatures.SyncToolSpecification tool2 = new McpServerFeatures.SyncToolSpecification(
				new McpSchema.Tool("tool2", "tool2 description", emptyJsonSchema), (exchange, request) -> callResponse);

		mcpServer.addTool(tool2);

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			assertThat(rootsRef.get()).containsAll(List.of(tool2.tool()));
		});

		mcpClient.close();
		mcpServer.close();
	}

	@Test
	void testInitialize() {

		var mcpServer = McpServer.sync(mcpServerTransportProvider).build();

		var mcpClient = clientBuilder.build();

		InitializeResult initResult = mcpClient.initialize();
		assertThat(initResult).isNotNull();

		mcpClient.close();
		mcpServer.close();
	}

}
