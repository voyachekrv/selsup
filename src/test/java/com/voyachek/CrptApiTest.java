package com.voyachek;

import com.voyachek.CrptApi.AppConfig;
import com.voyachek.CrptApi.DocumentId;
import com.voyachek.CrptApi.DocumentIntroductionFormat;
import com.voyachek.CrptApi.DocumentRussianProductIntroduction;
import com.voyachek.CrptApi.ProductGroup;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class CrptApiTest {

    private CrptApi.TokenCache cache;
    private CrptApi.CryptoSign cryptoSign;
    private CrptApi api;

    private AppConfig config;

    @BeforeEach
    void setUp() {
        cache = mock(CrptApi.TokenCache.class);
        cryptoSign = mock(CrptApi.CryptoSign.class);

        config = new AppConfig();
        config.setTimeUnit(TimeUnit.SECONDS);

        api = spy(new CrptApi(config, cache, cryptoSign, new SimpleMeterRegistry()));

        when(cache.get(eq("token"), any())).thenReturn("test-token");
    }

    @Test
    void testCreateDocumentSuccess() throws InterruptedException {
        DocumentRussianProductIntroduction document = new DocumentRussianProductIntroduction(
                CrptApi.DocumentTypeRussianProductIntroduction.CSV,
                ProductGroup.CLOTHES,
                "{\"test\":\"data\"}"
        );

        DocumentId expected = new DocumentId();
        expected.value = "12345";

        doReturn(expected).when(api).callPostApiWithMetrics(
                eq("createDocumentOfRussianProduct"),
                anyString(),
                any(),
                eq(DocumentId.class)
        );

        DocumentId actual = api.createDocumentOfRussianProduct(document, "mock-signature");

        assertThat(actual).isNotNull();
        assertThat(actual.value).isEqualTo("12345");
    }

    @Test
    void testCreateDocumentThrowsException() throws InterruptedException {
        DocumentRussianProductIntroduction document = new DocumentRussianProductIntroduction(
                CrptApi.DocumentTypeRussianProductIntroduction.CSV,
                ProductGroup.SHOES,
                "{\"test\":\"fail\"}"
        );

        // Имитируем исключение при вызове API
        doThrow(new CrptApi.CrptApiException(500))
                .when(api)
                .callPostApiWithMetrics(anyString(), anyString(), any(), eq(DocumentId.class));

        CrptApi.CrptApiException exception = assertThrows(
                CrptApi.CrptApiException.class,
                () -> api.createDocumentOfRussianProduct(document, "sig")
        );

        assertThat(exception.statusCode).isEqualTo(500);
        assertThat(exception.errorCode).isEqualTo(CrptApi.CrptApiException.ErrorCode.STATUS_CODE_ERROR);
    }

    @Test
    void testTokenFromCache() throws InterruptedException {
        when(cache.get(eq("token"), any())).thenReturn("cached-token");

        String token = api.createDocumentOfRussianProduct(
                new CrptApi.DocumentRussianProductIntroduction(
                        CrptApi.DocumentTypeRussianProductIntroduction.CSV,
                        CrptApi.ProductGroup.CLOTHES,
                        "{}"
                ),
                "signature"
        ).value;

        assertThat(token).isNotNull();
    }

    @Test
    void testTokenSignatureAndFetch() throws Exception {
        var fakeCert = new CrptApi.CertKeyPayload();
        fakeCert.data = "raw-cert-data";

        var signedCert = "signed-cert";
        var tokenPayload = new CrptApi.TokenPayload();
        tokenPayload.token = "signed-token-xyz";

        doReturn(fakeCert).when(api).fetchCertKey();
        when(cryptoSign.sign("raw-cert-data")).thenReturn(signedCert);
        doReturn(tokenPayload).when(api).fetchToken(any());

        when(cache.get(eq("token"), any())).thenAnswer(invocation -> {
            Function<String, String> loader = invocation.getArgument(1);
            return loader.apply("token");
        });

        String result = api.createDocumentOfRussianProduct(
                new CrptApi.DocumentRussianProductIntroduction(
                        CrptApi.DocumentTypeRussianProductIntroduction.CSV,
                        CrptApi.ProductGroup.CLOTHES,
                        "{}"
                ),
                "signature"
        ).value;

        assertThat(result).isNotNull();
    }
}

