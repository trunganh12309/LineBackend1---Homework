package vn.edu.topica.eco.api.middleware.controller;

import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Strings;
import org.skyscreamer.jsonassert.JSONAssert;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import vn.edu.topica.eco.api.base.util.JsonUtils;
import vn.edu.topica.eco.api.middleware.util.FileUtils;

import java.io.File;
import java.util.Collections;
import java.util.List;

import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

@ActiveProfiles({"test", "test-local"})
public class ApiSteps {
	
  private final String ACCESS_TOKEN = "access_token";
  private final String EXTRA = "extra";
  private final String DATA = "data";
  private final String FAKE_TOKEN = "sometoken";
  private final String SRC_PATH = "src/test/resources/";

  @Value("${mockServer}")
  boolean usingMockServer;

  @Value("${staging.url:}")
  String stagingUrl;

  final String localhost = "http://localhost:8081";

  static UriComponentsBuilder builder;
  static HttpMethod requestMethod;
  static ResponseEntity response;
  static HttpHeaders httpHeaders;

  static boolean updateData = false;

  static MockHttpServletRequestBuilder requestBuilder;
  static ResultActions resultActions;

  public MockMvc mockMvc;
  JsonUtils jsonUtils;

  @Autowired
  public ApiSteps(MockMvc mockMvc, JsonUtils jsonUtils) {
    this.mockMvc = mockMvc;
    this.jsonUtils = jsonUtils;
  }

  public static void createRequestUrl(String host, String method, String path) {
    String requestUrl = host + path;
    builder = UriComponentsBuilder.fromUriString(requestUrl);
    requestMethod = HttpMethod.valueOf(method.toUpperCase());
  }

  void createMockRequestBuilder(String method, String path) {
    requestBuilder = request(HttpMethod.valueOf(method.toUpperCase()), path);
  }

  static void excuteRequest(JsonNode body) {
    RestTemplate restTemplate = new RestTemplate();
    HttpEntity entity;
    httpHeaders.setContentType(MediaType.APPLICATION_JSON);

    MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
    converter.setSupportedMediaTypes(Collections.singletonList(MediaType.ALL));
    List<HttpMessageConverter<?>> messageConverters = Collections.singletonList(converter);
    restTemplate.setMessageConverters(messageConverters);

    entity = new HttpEntity<>(body, httpHeaders);
    response = restTemplate.exchange(builder.build().toUri(), requestMethod, entity, JsonNode.class);
  }

  void verifyResponse(String expectedResource, boolean strict) throws Exception {
    if (!Strings.isNullOrEmpty(expectedResource)) {
      String jsonExpected = FileUtils.readForString(expectedResource);
      if (usingMockServer) {
        resultActions.andExpect(content().json(jsonExpected, strict));
      } else {
        JSONAssert.assertEquals(jsonExpected, response.getBody().toString(), true);
      }
    }
  }

  public static void excuteRequestAndSaveToFile(String token, JsonNode body, String filePath) throws Exception {
    if (Strings.isNullOrEmpty(httpHeaders.getFirst(HttpHeaders.AUTHORIZATION))) {
      httpHeaders.setBearerAuth(token);
    }
    excuteRequest(body);
    saveToFile(filePath);
  }

  static void saveToFile(String path) throws Exception {
    String filePath = SRC_PATH + path;
    ObjectWriter writer = FileUtils.mapper.writer(new DefaultPrettyPrinter());
    try {
      ObjectNode objectNode = FileUtils.mapper.valueToTree(response.getBody());
      hideToken(objectNode);
      writer.writeValue(new File(filePath), objectNode);
    } catch (ClassCastException e) {
      writer.writeValue(new File(filePath), response.getBody());
    }
  }

  private static void hideToken(ObjectNode objectNode) {
    if (objectNode.hasNonNull(ACCESS_TOKEN)) {
      objectNode.remove(ACCESS_TOKEN);
      objectNode.put(ACCESS_TOKEN, FAKE_TOKEN);
    } else if (objectNode.hasNonNull(EXTRA) && objectNode.get(EXTRA).hasNonNull(ACCESS_TOKEN)) {
      ObjectNode extra = (ObjectNode) objectNode.get(EXTRA);
      extra.remove(ACCESS_TOKEN);
      extra.put(ACCESS_TOKEN, FAKE_TOKEN);
    } else if (objectNode.hasNonNull(DATA) && objectNode.get(DATA).hasNonNull(ACCESS_TOKEN)) {
      ObjectNode data = (ObjectNode) objectNode.get(DATA);
      data.remove(ACCESS_TOKEN);
      data.put(ACCESS_TOKEN, FAKE_TOKEN);
    }
  }
}
