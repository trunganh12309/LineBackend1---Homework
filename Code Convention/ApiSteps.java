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

  void verifyResponse(String expectedResource, boolean b) throws Exception {
    if (!Strings.isNullOrEmpty(expectedResource)) {
      String jsonExpected = FileUtils.readForString(expectedResource);
      if (usingMockServer) {
        resultActions
          .andExpect(content().json(jsonExpected, b));
      } else {
        JSONAssert.assertEquals(jsonExpected, response.getBody().toString(), true);
      }
    }
  }

  public static void excuteRequestAndSaveToFile(String token, JsonNode body, String filePath) throws Exception {
    if (Strings.isNullOrEmpty(ApiSteps.httpHeaders.getFirst(HttpHeaders.AUTHORIZATION))) {
      ApiSteps.httpHeaders.setBearerAuth(token);
    }
    ApiSteps.excuteRequest(body);
    ApiSteps.saveToFile(filePath);
  }

  static void saveToFile(String path) throws Exception {
    String filePath = "src/test/resources/" + path;
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
    if (objectNode.hasNonNull("access_token")) {
      objectNode.remove("access_token");
      objectNode.put("access_token", "sometoken");
    } else if (objectNode.hasNonNull("extra") && objectNode.get("extra").hasNonNull("access_token")) {
      ObjectNode extra = (ObjectNode) objectNode.get("extra");
      extra.remove("access_token");
      extra.put("access_token", "sometoken");
    } else if (objectNode.hasNonNull("data") && objectNode.get("data").hasNonNull("access_token")) {
      ObjectNode data = (ObjectNode) objectNode.get("data");
      data.remove("access_token");
      data.put("access_token", "sometoken");
    }
  }
}
