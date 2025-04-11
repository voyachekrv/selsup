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

/**
 * API для создания документов в системе Честного знака
 */
public class CrptApi {

    /** Логгер */
    private static final Logger logger = LoggerFactory.getLogger(CrptApi.class);

    /** Кэш */
    public interface Cache {
        /** Получение значения из кэша */
        String get(String key, Function<String, String> value);

        /** Удаление значения из кэша */
        void delete(String key);
    }

    /** API подписи ключа */
    public interface CryptoSign {
        String sign(String value);
    }

    /** Исключения */
    public static class CrptApiException extends RuntimeException {
        public enum ErrorCode {
            /** Ошибка подключения */
            CONNECTION_ERROR,
            /** Ошибка HTTP-статуса */
            STATUS_CODE_ERROR,
            /** Ошибка формата вывода */
            RESPONSE_FORMAT_ERROR,
            /** Ошибка ввода-вывода */
            IO_ERROR
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

    /** Конфигурация приложения */
    public static class AppConfig {
        /** Протокол хоста API */
        public final String apiProtocol = "http";
        /** Хост API */
        public final String apiHost = "localhost:3000";
        /** Лимит запросов */
        public final int requestLimit = 100;
        /** Единица времени */
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

        @Override
        public String toString() {
            return "{value=" + value + '\"' + '}';
        }
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

    /**
     * DTO результата запроса авторизации
     */
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

    /** Конфигурация приложения */
    private final AppConfig appConfig;

    /** Маппер объектов */
    private final ObjectMapper objectMapper;

    /** Кэш*/
    private Cache cache;
    /** API подписи ключа */
    private CryptoSign cryptoSign;

    /** Метрики для метода вызова API */
    private static class Metrics {
        public final Counter successCounter;
        public final Counter failureCounter;
        public final Timer executionTimer;

        private Metrics(MeterRegistry meterRegistry, String methodName) {
            this.successCounter = Counter.builder(String.format("crptapi.%s.success", methodName))
                    .tags("method", methodName)
                    .description("Количество успешных вызовов метода")
                    .register(meterRegistry);

            this.failureCounter = Counter.builder(String.format("crptapi.%s.failure", methodName))
                    .tags("method", methodName)
                    .description("Количество неудачных вызовов метода")
                    .register(meterRegistry);

            this.executionTimer = Timer.builder(String.format("crptapi.%s.duration", methodName))
                    .tags("method", methodName)
                    .description("Длительность работы метода")
                    .register(meterRegistry);
        }
    }

    /** Мап метрик для методов API */
    Map<String, Metrics> metricsMap = new HashMap<>();

    /**
     * @param appConfig Конфигурация приложения
     * @param cache Кэш
     * @param cryptoSign API подписи ключа
     * @param meterRegistry Регистратор метрик
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
     * Вызов POST-метода API честного знака
     * @param <R> Тип возвращаемого значения
     * @param methodName Наименование метода
     * @param url URL метода API
     * @param payload Тело метода API в виде строки
     * @param returnType Тип возвращаемого значения
     */
    private <R> R callPostApi(String methodName, String url, String payload, Class<R> returnType) {
        var metrics = metricsMap.get(methodName);
        return metrics.executionTimer.record(() -> {
            logger.info("{}: payload = {}", methodName, payload);
            try {
                acquirePermit();

                var request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + getToken())
                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                        .build();

                var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 401) {
                    cache.delete("token");
                    response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                }

                if (response.statusCode() >= 400) {
                    System.out.println(response.statusCode());
                    throw new CrptApiException(response.statusCode());
                }

                var bodyString = response.body();

                var responseBody = objectMapper.readValue(bodyString, returnType);

                logger.info("success: {}: result = {}", methodName, responseBody);

                return responseBody;
            } catch (Exception e) {
                logger.error("error on method: {}, error: ", methodName, e);
                metrics.failureCounter.increment();
                throw new CrptApiException(e, CrptApiException.ErrorCode.IO_ERROR);
            }
        });
    }

    /**
     * Создаёт документ для ввода в оборот товара, произведенного в РФ.
     *
     * @param document Объект документа.
     * @param signature Строка с открепленной подписью в формате base64.
     * @return объект `DocumentId`, содержащий статус ответа и тело ответа.
     */
    public DocumentId createDocumentOfRussianProduct(DocumentRussianProductIntroduction document, String signature) {
        try {
            var payload = preparePayload(document, signature);
            return callPostApi(
                    "createDocumentOfRussianProduct",
                    String.format("%s://%s/api/v3/lk/documents/create", appConfig.apiProtocol, appConfig.apiHost),
                    payload,
                    DocumentId.class
            );
        } catch (JsonProcessingException e) {
            throw new CrptApiException(e, CrptApiException.ErrorCode.RESPONSE_FORMAT_ERROR);
        }
    }

    /**
     * Получение токена из кэша, если токена не найден, то производится подпись нового токена
     */
    private String getToken() {
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
