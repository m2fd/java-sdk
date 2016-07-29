/**
 * Copyright 2015 IBM Corp. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.ibm.watson.developer_cloud.service;

import com.google.gson.JsonObject;
import com.ibm.watson.developer_cloud.http.*;
import com.ibm.watson.developer_cloud.service.exception.*;
import com.ibm.watson.developer_cloud.util.CredentialUtils;
import com.ibm.watson.developer_cloud.util.RequestUtils;
import com.ibm.watson.developer_cloud.util.ResponseConverterUtils;
import com.ibm.watson.developer_cloud.util.ResponseUtils;
import jersey.repackaged.jsr166e.CompletableFuture;
import okhttp3.*;
import okhttp3.Request.Builder;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Watson service abstract common functionality of various Watson Services. It handle authentication
 * and default url
 *
 * @see <a href="http://www.ibm.com/smarterplanet/us/en/ibmwatson/developercloud/"> IBM Watson
 *      Developer Cloud</a>
 */
public abstract class WatsonService {

  private static final String URL = "url";
  private static final String PATH_AUTHORIZATION_V1_TOKEN = "/v1/token";
  private static final String AUTHORIZATION = "authorization";
  private static final String MESSAGE_ERROR_3 = "message";
  private static final String MESSAGE_ERROR_2 = "error_message";
  private static final String BASIC = "Basic ";
  private static final Logger LOG = Logger.getLogger(WatsonService.class.getName());
  private String apiKey;
  private OkHttpClient client;
  private String endPoint;
  private final String name;
  private Headers defaultHeaders = null;
  private boolean skipAuthentication = false;
  private static ProxyConfiguration proxyConfiguration = null;

  /** The Constant MESSAGE_CODE. */
  protected static final String MESSAGE_CODE = "code";

  /** The Constant MESSAGE_ERROR. */
  protected static final String MESSAGE_ERROR = "error";

  /** The Constant VERSION. */
  protected static final String VERSION = "version";

  /**
   * Instantiates a new Watson service.
   *
   * @param name the service name
   */
  public WatsonService(String name) {
    this.name = name;
    this.apiKey = CredentialUtils.getAPIKey(name);
    this.client = configureHttpClient();
    String url = CredentialUtils.getAPIUrl(name);
    if (url != null && !url.isEmpty()) {
      // The VCAP_SERVICES will typically contain a url. If present use it.
      setEndPoint(url);
    }
  }

   /**
   * Configures the HTTP client.
   *
   * @return the HTTP client
   */
  protected OkHttpClient configureHttpClient() {
    final OkHttpClient.Builder builder = new OkHttpClient.Builder();

    final CookieManager cookieManager = new CookieManager();
    cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);

    builder.cookieJar(new JavaNetCookieJar(cookieManager));

    builder.connectTimeout(60, TimeUnit.SECONDS);
    builder.writeTimeout(60, TimeUnit.SECONDS);
    builder.readTimeout(90, TimeUnit.SECONDS);

    if (proxyConfiguration != null) {

      builder.proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(
              proxyConfiguration.getHostname(), proxyConfiguration.getPort())));

      if (proxyConfiguration.getCredentials() != null) {

        Authenticator authenticator = new Authenticator() {
          @Override
          public Request authenticate(Route route, Response response)
                  throws IOException {
            return response.request().newBuilder()
                    .header("Proxy-Authorization", proxyConfiguration.getCredentials()).build();
          }
        };
        builder.proxyAuthenticator(authenticator);

      }
    }

    return builder.build();
  }

  /**
   * Execute the HTTP request. Okhttp3 compliant.
   *
   * @param request the HTTP request
   *
   * @return the HTTP response
   */
  private Call createCall(Request request) {
    final Request.Builder builder = request.newBuilder();

    if (RequestUtils.isRelative(request)) {
      builder.url(RequestUtils.replaceEndPoint(request.url().toString(), getEndPoint()));
    }

    String userAgent = RequestUtils.getUserAgent();

    if (defaultHeaders != null) {
      for (String key : defaultHeaders.names()) {
        builder.header(key, defaultHeaders.get(key));
      }
      if (defaultHeaders.get(HttpHeaders.USER_AGENT) != null) {
        userAgent += " " + defaultHeaders.get(HttpHeaders.USER_AGENT);
      }

    }

    builder.header(HttpHeaders.USER_AGENT, userAgent);

    setAuthentication(builder);

    final Request newRequest = builder.build();
    return client.newCall(newRequest);

  }

  /**
   * Creates the service call.
   *
   * @param <T> the generic type
   * @param request the request
   * @param converter the converter
   * @return the service call
   */
  protected final <T> ServiceCall<T> createServiceCall(final Request request, final ResponseConverter<T> converter) {
    final Call call = createCall(request);
    return new ServiceCall<T>() {
      @Override
      public T execute() {
        try {
          Response response = call.execute();
          return processServiceCall(converter, response);
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }

      @Override
      public void enqueue(final ServiceCallback<? super T> callback) {
        call.enqueue(new Callback() {
          @Override
          public void onFailure(Call call, IOException e) {
            callback.onFailure(e);
          }

          @Override
          public void onResponse(Call call, Response response) {
            try {
              callback.onResponse(processServiceCall(converter, response));
            } catch (Exception e) {
              callback.onFailure(e);
            }
          }


        });
      }

      @Override
      public CompletableFuture<T> rx() {
        final CompletableFuture<T> completableFuture = new CompletableFuture<T>();

        call.enqueue(new Callback() {
          @Override
          public void onFailure(Call call, IOException e) {
            completableFuture.completeExceptionally(e);
          }

          @Override
          public void onResponse(Call call, Response response) {
            try {
              completableFuture.complete(processServiceCall(converter, response));
            } catch (Exception e) {
              completableFuture.completeExceptionally(e);
            }
          }
        });

        return completableFuture;
      }

      @Override
      protected void finalize() throws Throwable {
        super.finalize();

        if (!call.isExecuted()) {
          final Request r = call.request();
          LOG.warning(r.method() + " request to " + r.url() + " has not been sent. Did you forget to call execute()?");
        }
      }
    };
  }

  /**
   * Gets the API key.
   *
   *
   * @return the API key
   */
  protected String getApiKey() {
    return apiKey;
  }

  /**
   * Gets the API end point.
   *
   *
   * @return the API end point
   */
  public String getEndPoint() {
    return endPoint;
  }

  /**
   * Gets an authorization token that can be use to authorize API calls.
   *
   *
   * @return the token
   */
  public ServiceCall<String> getToken() {
    HttpUrl url = HttpUrl.parse(getEndPoint()).newBuilder().setPathSegment(0, AUTHORIZATION).build();
    Request request = RequestBuilder.get(url + PATH_AUTHORIZATION_V1_TOKEN)
            .header(HttpHeaders.ACCEPT, HttpMediaType.TEXT_PLAIN).query(URL, getEndPoint()).build();

    return createServiceCall(request, ResponseConverterUtils.getString());
  }

  /**
   * Gets the error message from a JSON response
   *
   * <pre>
   * {
   *   code: 400
   *   error: 'bad request'
   * }
   * </pre>
   *
   * @param response the HTTP response
   * @return the error message from the JSON object
   */
  private String getErrorMessage(Response response) {
    String error = ResponseUtils.getString(response);
    try {

      final JsonObject jsonObject = ResponseUtils.getJsonObject(error);
      if (jsonObject.has(MESSAGE_ERROR)) {
        error = jsonObject.get(MESSAGE_ERROR).getAsString();
      } else if (jsonObject.has(MESSAGE_ERROR_2)) {
        error = jsonObject.get(MESSAGE_ERROR_2).getAsString();
      } else if (jsonObject.has(MESSAGE_ERROR_3)) {
        error = jsonObject.get(MESSAGE_ERROR_3).getAsString();
      }
    } catch (final Exception e) {
      // Ignore any kind of exception parsing the json and use fallback String version of response
    }

    return error;
  }

  /**
   * Gets the name.
   *
   * @return the name
   */
  public String getName() {
    return name;
  }


  /**
   * Sets the API key.
   *
   * @param apiKey the new API key
   */
  public void setApiKey(String apiKey) {
    this.apiKey = apiKey;
  }

  /**
   * Sets the authentication. Okhttp3 compliant.
   *
   * @param builder the new authentication
   */
  protected void setAuthentication(Builder builder) {
    if (getApiKey() == null) {
      if (skipAuthentication) // This may be a proxy or some other component where the developer has
        return; // chosen to skip authentication with the service
      throw new IllegalArgumentException("apiKey or username and password were not specified");
    }
    builder.addHeader(HttpHeaders.AUTHORIZATION, apiKey.startsWith(BASIC) ? apiKey : BASIC + apiKey);
  }

  /**
   * Sets the end point.
   *
   * @param endPoint the new end point
   */
  public void setEndPoint(final String endPoint) {
    if (endPoint != null && !endPoint.isEmpty() && endPoint.endsWith("/")) {
      this.endPoint = endPoint.substring(0, endPoint.length() - 1);
    } else {
      this.endPoint = endPoint;
    }
  }

  /**
   * Sets the username and password.
   *
   * @param username the username
   * @param password the password
   */
  public void setUsernameAndPassword(String username, String password) {
    apiKey = Credentials.basic(username, password);
  }

  /**
   * Set the default headers to be used on every HTTP request.
   *
   * @param headers name value pairs of headers
   */
  public void setDefaultHeaders(Map<String, String> headers) {
    if (headers == null) {
      defaultHeaders = null;
    } else {
      defaultHeaders = Headers.of(headers);
    }
  }

  /*
   * (non-Javadoc)
   *
   * @see java.lang.Object#toString()
   */
  @Override
  public String toString() {
    final StringBuilder builder = new StringBuilder()
            .append(name)
            .append(" [");

    if (endPoint != null) {
      builder.append("endPoint=")
              .append(endPoint);
    }

    return builder.append(']').toString();
  }


  /**
   * Process service call.
   *
   * @param <T> the generic type
   * @param converter the converter
   * @param response the response
   * @return the t
   */
  protected <T> T processServiceCall(final ResponseConverter<T> converter, Response response) {
    if (response.isSuccessful())
      return converter.convert(response);

    // There was a Client Error 4xx or a Server Error 5xx
    // Get the error message and create the exception
    final String error = getErrorMessage(response);
    LOG.log(Level.SEVERE, response.request().method() + " " + response.request().url().toString() + ", status: "
            + response.code() + ", error: " + error);

    switch (response.code()) {
      case HttpStatus.BAD_REQUEST: // HTTP 400
        throw new BadRequestException(error != null ? error : "Bad Request", response);
      case HttpStatus.UNAUTHORIZED: // HTTP 401
        throw new UnauthorizedException("Unauthorized: Access is denied due to invalid credentials", response);
      case HttpStatus.FORBIDDEN: // HTTP 403
        throw new ForbiddenException(error != null ? error : "Forbidden: Service refuse the request", response);
      case HttpStatus.NOT_FOUND: // HTTP 404
        throw new NotFoundException(error != null ? error : "Not found", response);
      case HttpStatus.NOT_ACCEPTABLE: // HTTP 406
        throw new ForbiddenException(error != null ? error : "Forbidden: Service refuse the request", response);
      case HttpStatus.CONFLICT: // HTTP 409
        throw new ConflictException(error != null ? error : "", response);
      case HttpStatus.REQUEST_TOO_LONG: // HTTP 413
        throw new RequestTooLargeException(error != null ? error
                : "Request too large: The request entity is larger than the server is able to process", response);
      case HttpStatus.UNSUPPORTED_MEDIA_TYPE: // HTTP 415
        throw new UnsupportedException(error != null ? error : "Unsupported Media Type", response);
      case HttpStatus.TOO_MANY_REQUESTS: // HTTP 429
        throw new TooManyRequestsException(error != null ? error : "Too many requests", response);
      case HttpStatus.INTERNAL_SERVER_ERROR: // HTTP 500
        throw new InternalServerErrorException(error != null ? error : "Internal Server Error", response);
      case HttpStatus.SERVICE_UNAVAILABLE: // HTTP 503
        throw new ServiceUnavailableException(error != null ? error : "Service Unavailable", response);
      default: // other errors
        throw new ServiceResponseException(response.code(), error, response);
    }
  }

  /**
   * Sets the skip authentication.
   *
   * @param skipAuthentication the new skip authentication
   */
  public void setSkipAuthentication(boolean skipAuthentication) {
    this.skipAuthentication = skipAuthentication;
  }

  /**
   * Sets the proxy configuration.
   *
   * @param proxyConfiguration	the proxy configuration
   */
  public static void setProxyConfiguration(ProxyConfiguration proxyConfiguration) {
    WatsonService.proxyConfiguration = proxyConfiguration;
  }

  public void reconfigureHttpClient() {
    this.client = configureHttpClient();
  }
}
