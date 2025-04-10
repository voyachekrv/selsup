package com.voyachek;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.micrometer.core.instrument.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/*
* 1. Получить токен с использованием кэша
* "key": "token",
* Алгоритм функции value:
* 1. Получить токен из кэша
* 2. Если токена нет, то подписываем присланный ключ (get key), получаем токен через апи (get token)
*
* Сделать свои exceptions
* Сделать класс конфигураций
* Сделать удаление токена из кэша, если пришел ответ 401
* Кэш будет храниться 10 часов
* добавить slf4j
* метрики через микрометр
* трассировку через open telemetry
*/

public class CrptApi {

    private static final Logger logger = LoggerFactory.getLogger(CrptApi.class);

    public interface Cache {
        String get(String key, Function<String, String> value);

        void delete(String key);
    }

    public interface CryptoSign {
        String sign(String value);
    }

    public static class CrptApiException extends RuntimeException {
        public enum ErrorCode {
            CONNECTION_ERROR,
            STATUS_CODE_ERROR,
            RESPONSE_FORMAT_ERROR
        }

        public int statusCode = -1;
        public ErrorCode errorCode;

        public CrptApiException(Throwable cause, ErrorCode errorCode) {
            super(cause);
            this.errorCode = errorCode;
        }

        public CrptApiException(int statusCode) {
            this.errorCode = ErrorCode.STATUS_CODE_ERROR;
            this.statusCode = statusCode;
        }
    }

    public static class AppConfig {
        public final String apiProtocol = "http";
        public final String apiHost = "localhost:3000";
        public final int requestLimit = 100;
        public final TimeUnit timeUnit = TimeUnit.SECONDS;
    }

    /**
     * Представление формата документа для ввода в оборот российского товара
     */
    public static class DocumentIntroductionFormat {
        public String documentTypeFormat;
        public String documentTypeRussianProduct;

        public DocumentIntroductionFormat(String documentTypeFormat, String documentTypeRussianProduct) {
            this.documentTypeFormat = documentTypeFormat;
            this.documentTypeRussianProduct = documentTypeRussianProduct;
        }
    }

    /**
     * Типы документов для ввода в оборот российского товара
     */
    public static final class DocumentTypeRussianProductIntroduction {
        public static final DocumentIntroductionFormat MANUAL = new DocumentIntroductionFormat("MANUAL", "LP_INTRODUCE_GOODS");
        public static final DocumentIntroductionFormat CSV = new DocumentIntroductionFormat("CSV", "LP_INTRODUCE_GOODS_CSV");
        public static final DocumentIntroductionFormat XML = new DocumentIntroductionFormat("XML", "LP_INTRODUCE_GOODS_XML");
    }

    /**
     * Продуктовые группы
     */
    public static final class ProductGroup {
        public static final String CLOTHES = "clothes";
        public static final String SHOES = "shoes";
        public static final String TOBACCO = "tobacco";
        public static final String PERFUMERY = "perfumery";
        public static final String TIRES = "tires";
        public static final String ELECTRONICS = "electronics";
        public static final String PHARMA = "pharma";
        public static final String MILK = "milk";
        public static final String BICYCLES = "bicycles";
        public static final String WHEELCHAIRS = "wheelchairs";
    }

    /**
     * Документ на ввод в оборот российского товара
     */
    public static class DocumentRussianProductIntroduction {
        public DocumentIntroductionFormat documentType;
        public String productDocument;
        public String productGroup;

        public DocumentRussianProductIntroduction(DocumentIntroductionFormat documentType, String productGroup, String productDocument) {
            this.documentType = documentType;
            this.productDocument = productDocument;
            this.productGroup = productGroup;
        }
    }

    /**
     * Результат метода создания документа на ввод в оборот российского товара
     */
    public static final class DocumentId {
        /**
         * Уникальный идентификатор документа в ИС МП
         */
        public String value;
    }

    /**
     * Тело метода создания документа на ввод в оборот российского товара
     */
    private static class MethodPayload {
        public String document_format;
        public String product_document;
        public String product_group;
        public String type;
        public String signature;

        public MethodPayload(String document_format, String product_document, String product_group, String type, String signature) {
            this.document_format = document_format;
            this.product_document = product_document;
            this.product_group = product_group;
            this.type = type;
            this.signature = signature;
        }
    }

    private static class CertKeyPayload {
        public String uuid;
        public String data;
    }

    /** Заданный период времени в миллисекундах */
    private final long periodMillis;
    /** Время старта окна */
    private long windowStart;
    /** Текущее количество запросов */
    private int currentCount;

    /** HTTP-клиент */
    private final HttpClient httpClient;

    private final AppConfig appConfig;

    private final ObjectMapper objectMapper;

    private Cache cache;
    private CryptoSign cryptoSign;

    private static class Metrics {
        public final Counter successCounter;
        public final Counter failureCounter;
        public final Timer executionTimer;

        private Metrics(MeterRegistry meterRegistry, String methodName) {
            this.successCounter = Counter.builder("crptapi.create.success")
                    .tags("method", methodName)
                    .description("Количество успешных вызовов создания документа")
                    .register(meterRegistry);

            this.failureCounter = Counter.builder("crptapi.create.failure")
                    .tags("method", methodName)
                    .description("Количество неудачных вызовов создания документа")
                    .register(meterRegistry);

            this.executionTimer = Timer.builder("crptapi.create.duration")
                    .tags("method", methodName)
                    .description("Длительность создания документа")
                    .register(meterRegistry);
        }
    }

    Map<String, Metrics> metricsMap = new HashMap<>();

    /**
     */
    public CrptApi(AppConfig appConfig, CrptApi.Cache cache, CrptApi.CryptoSign cryptoSign, MeterRegistry meterRegistry) {
        this.metricsMap.put("createDocumentOfRussianProduct", new Metrics(meterRegistry, "createDocumentOfRussianProduct"));

        this.periodMillis = appConfig.timeUnit.toMillis(1);
        this.windowStart = System.currentTimeMillis();
        this.currentCount = 0;
        this.httpClient = HttpClient.newHttpClient();
        this.appConfig = appConfig;
        this.objectMapper = new ObjectMapper();

        this.cache = cache;
        this.cryptoSign = cryptoSign;
    }

    /**
     * Обеспечение ограничения по числу запросов.
     * Если достигнут лимит, вызывающий поток блокируется до начала следующего интервала.
     */
    private synchronized void acquirePermit() throws InterruptedException {
        while (true) {
            long now = System.currentTimeMillis();
            if (now - windowStart >= periodMillis) {
                windowStart = now;
                currentCount = 0;
                notifyAll();
            }
            if (currentCount < this.appConfig.requestLimit) {
                currentCount++;
                break;
            }
            long waitTime = periodMillis - (now - windowStart);
            if (waitTime <= 0) {
                wait(1);
            } else {
                wait(waitTime);
            }
        }
    }

    /**
     * Создаёт документ для ввода в оборот товара, произведенного в РФ.
     *
     * @param document Объект документа.
     * @param signature Строка с открепленной подписью в формате base64.
     * @return объект `DocumentId`, содержащий статус ответа и тело ответа.
     */
    public DocumentId createDocumentOfRussianProduct(DocumentRussianProductIntroduction document, String signature) {
        var metrics = metricsMap.get("createDocumentOfRussianProduct");
        return metrics.executionTimer.record(() -> {
            logger.info("Попытка создания документа: тип = {}, группа = {}", document.documentType.documentTypeRussianProduct, document.productGroup);
            try {
                acquirePermit();

                var payload = preparePayload(document, signature);

                var result = createDocument(payload, this.getToken());

                logger.info("Документ успешно создан. ID = {}", result.value);
                metrics.successCounter.increment();

                return result;
            } catch (Exception e) {
                logger.error("Ошибка при создании документа", e);
                metrics.failureCounter.increment();
                throw new CrptApiException(e, CrptApiException.ErrorCode.CONNECTION_ERROR);
            }
        });
    }

    private String getToken() throws IOException, InterruptedException {
        return cache.get("token", (token) -> {
            CertKeyPayload response = null;
            try {
                response = fetchCertKey();
            } catch (IOException e) {
                return cryptoSign.sign(response.data);
            } catch (InterruptedException e) {
                return cryptoSign.sign(response.data);
            }
            return cryptoSign.sign(response.data);
        });
    }

    /**
     * Подготовка тело для метода создания документа
     */
    private String preparePayload(DocumentRussianProductIntroduction document, String signature) throws JsonProcessingException {
        var payload = new MethodPayload(
                document.documentType.documentTypeFormat,
                document.productDocument,
                document.productDocument,
                document.documentType.documentTypeRussianProduct,
                signature
        );
        return objectMapper.writeValueAsString(payload);
    }

    /**
     * Создание документа на основе тела
     */
    private DocumentId createDocument(String payload, String token) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(String.format("%s://%s/api/v3/lk/documents/create", appConfig.apiProtocol, appConfig.apiHost)))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 401) {
            cache.delete("token");
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        }

        if (response.statusCode() >= 400) {
            throw new CrptApiException(response.statusCode());
        }

        var body = response.body();

        return mapResponse(body);
    }

    /**
     * Маппинг ответа от API в
     */
    private DocumentId mapResponse(String response) throws JsonProcessingException {
        return objectMapper.readValue(response, DocumentId.class);
    }

    private CertKeyPayload fetchCertKey() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(String.format("%s://%s/api/v3/auth/cert/key", appConfig.apiProtocol, appConfig.apiHost)))
                .GET()
                .header("Accept", "application/json") // можно убрать, если не требуется
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        var body = response.body();

        return objectMapper.readValue(body, CertKeyPayload.class);
    }
}
