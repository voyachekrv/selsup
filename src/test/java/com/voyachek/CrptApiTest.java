package com.voyachek;

import com.voyachek.CrptApi.DocumentId;
import com.voyachek.CrptApi.DocumentRussianProductIntroduction;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.voyachek.CrptApi.DocumentTypeRussianProductIntroduction.MANUAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class CrptApiTest {
    private CrptApi api;
    private CrptApi.TokenCache tokenCache;
    private CrptApi.CryptoSign cryptoSign;
    private HttpClient httpClient;
    private SimpleMeterRegistry meterRegistry;
    private CrptApi.AppConfig appConfig;

    @BeforeEach
    public void setUp() throws Exception {
        meterRegistry = new SimpleMeterRegistry();
        tokenCache = mock(CrptApi.TokenCache.class);
        cryptoSign = mock(CrptApi.CryptoSign.class);
        appConfig = new CrptApi.AppConfig();

        appConfig.setApiProtocol("http");
        appConfig.setApiHost("localhost:3000");

        api = new CrptApi(appConfig, tokenCache, cryptoSign, meterRegistry);

        httpClient = mock(HttpClient.class);
        Field clientField = CrptApi.class.getDeclaredField("httpClient");
        clientField.setAccessible(true);
        clientField.set(api, httpClient);
    }

    @Test
    public void testSuccessCreateDocumentOfRussianProduct() throws Exception {
        var dummyToken = "dummy-token";
        when(tokenCache.get(eq("token"), any())).thenReturn(dummyToken);

        var documentId = new DocumentId();
        documentId.value = "doc-123";

        var httpResponse = mock(HttpResponse.class);
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("{\"value\":\"doc-123\"}");

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        var doc = new DocumentRussianProductIntroduction(
                MANUAL,
                "group1",
                "document-content"
        );

        var result = api.createDocumentOfRussianProduct(doc, "signature123");

        assertNotNull(result);
        assertEquals("doc-123", result.value);
        verify(tokenCache).get(eq("token"), any());
    }

    @Test
    public void testErrorCreatingDocumentApiSide() throws Exception {
        var dummyToken = "dummy-token";
        when(tokenCache.get(eq("token"), any())).thenReturn(dummyToken);

        var httpResponse = mock(HttpResponse.class);
        when(httpResponse.statusCode()).thenReturn(500);
        when(httpResponse.body()).thenReturn("Internal Server Error");

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        DocumentRussianProductIntroduction doc = new DocumentRussianProductIntroduction(
                MANUAL,
                "group1",
                "document-content"
        );

        CrptApi.CrptApiException ex = assertThrows(CrptApi.CrptApiException.class, () ->
                api.createDocumentOfRussianProduct(doc, "signature123")
        );
        assertEquals(500, ex.statusCode);
    }

    @Test
    public void testErrorObtainingStringForCertificateSigning() throws Exception {
        when(tokenCache.get(eq("token"), any())).thenAnswer(invocation -> {
            Function<String, String> supplier = invocation.getArgument(1);
            return supplier.apply(null);
        });

        var certKeyResponse = mock(HttpResponse.class);
        when(certKeyResponse.statusCode()).thenReturn(200);
        when(certKeyResponse.body()).thenReturn("{\"uuid\":\"uuid-123\", \"data\":\"data-to-sign\"}");
        when(httpClient.send(argThat(req -> req.uri().toString().contains("/api/v3/auth/cert/key")),
                any(HttpResponse.BodyHandler.class)))
                .thenReturn(certKeyResponse);

        when(cryptoSign.sign("data-to-sign")).thenThrow(new RuntimeException("Signing error"));

        var doc = new DocumentRussianProductIntroduction(
                MANUAL,
                "group1",
                "document-content"
        );

        var ex = assertThrows(RuntimeException.class, () ->
                api.createDocumentOfRussianProduct(doc, "signature123")
        );
        assertTrue(ex.getMessage().contains("Signing error"));
    }

    @Test
    public void testErrorObtainingToken() throws Exception {
        when(tokenCache.get(eq("token"), any())).thenAnswer(invocation -> {
            Function<String, String> supplier = invocation.getArgument(1);
            return supplier.apply(null);
        });

        var certKeyResponse = mock(HttpResponse.class);
        when(certKeyResponse.statusCode()).thenReturn(401);
        when(certKeyResponse.body()).thenReturn("Unauthorized");

        when(httpClient.send(argThat(req -> req.uri().toString().contains("/api/v3/auth/cert/key")),
                any(HttpResponse.BodyHandler.class)))
                .thenReturn(certKeyResponse);

        var doc = new DocumentRussianProductIntroduction(
                MANUAL,
                "group1",
                "document-content"
        );

        var ex = assertThrows(CrptApi.CrptApiException.class, () ->
                api.createDocumentOfRussianProduct(doc, "signature123")
        );
        assertEquals(401, ex.statusCode);
    }

    @Test
    public void testBlockingTokenBucketRateLimiter() throws InterruptedException {
        long capacity = 1;
        long refillTokens = 1;
        long refillPeriod = 1000;
        var limiter =
                new CrptApi.BlockingTokenBucketRateLimiter(capacity, refillTokens, refillPeriod, TimeUnit.MILLISECONDS);

        limiter.acquire();
        long startTime = System.currentTimeMillis();
        limiter.acquire();

        long duration = System.currentTimeMillis() - startTime;
        assertTrue(duration >= refillPeriod * 0.8, "Время ожидания должно быть приблизительно не меньше периода наполнения");
    }
}

