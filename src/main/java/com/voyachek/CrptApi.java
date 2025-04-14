package com.voyachek;

import com.fasterxml.jackson.core.JacksonException;
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
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * API для создания документов в системе Честного знака
 */
public class CrptApi {

    /** Логгер */
    private static final Logger logger = LoggerFactory.getLogger(CrptApi.class);

    /** Кэш */
    public interface TokenCache {
        /** Получение значения из кэша */
        String get(String key, Function<String, String> value);

        /** Удаление значения из кэша */
        void delete(String key);
    }

    /** API подписи ключа */
    public interface CryptoSign {
        String sign(String value);
    }

    /**
     * Потокобезопасный блокирующий ограничитель скорости на основе алгоритма корзины токенов.
     */
    public static class BlockingTokenBucketRateLimiter {
        private final long capacity;
        private final long refillTokens;
        private final long refillPeriodMillis;
        private long availableTokens;
        private long lastRefillTime;
        private final Lock lock = new ReentrantLock();

        /**
         * @param capacity максимальное количество токенов в корзине
         * @param refillTokens количество токенов, добавляемых при каждом пополнении
         * @param refillPeriod периодичность пополнения токенов
         * @param timeUnit единицы измерения периода пополнения
         */
        public BlockingTokenBucketRateLimiter(long capacity, long refillTokens, long refillPeriod, TimeUnit timeUnit) {
            this.capacity = capacity;
            this.refillTokens = refillTokens;
            this.refillPeriodMillis = timeUnit.toMillis(refillPeriod);
            this.availableTokens = capacity;
            this.lastRefillTime = System.currentTimeMillis();
        }

        /**
         * Запрашивает один токен у ограничителя. Если токенов нет — поток блокируется до тех пор, пока не появится доступный токен.
         */
        public void acquire() throws InterruptedException {
            acquire(1);
        }

        /**
         * Запрашивает указанное количество токенов у ограничителя.
         * Если недостаточно токенов — поток блокируется до их появления.
         * @param permits количество необходимых токенов
         */
        public void acquire(int permits) throws InterruptedException {
            while (true) {
                lock.lock();
                try {
                    refillTokens();

                    if (availableTokens >= permits) {
                        availableTokens -= permits;
                        return;
                    }
                } finally {
                    lock.unlock();
                }

                Thread.sleep(calculateWaitTime(permits));
            }
        }

        /**
         * Пополняет количество доступных токенов на основе времени,
         * прошедшего с последнего пополнения.
         */
        private void refillTokens() {
            long now = System.currentTimeMillis();
            long elapsed = now - lastRefillTime;

            if (elapsed > refillPeriodMillis) {
                long refillCount = elapsed / refillPeriodMillis;
                availableTokens = Math.min(capacity, availableTokens + refillCount * refillTokens);
                lastRefillTime = now;
            }
        }

        /**
         * Вычисляет примерное время ожидания (в миллисекундах), необходимое до появления нужного количества токенов.
         */
        private long calculateWaitTime(int permits) {
            if (availableTokens >= permits) return 0;

            long tokensNeeded = permits - availableTokens;
            long refillsNeeded = (tokensNeeded + refillTokens - 1) / refillTokens;
            return refillsNeeded * refillPeriodMillis;
        }
    }

    /** Исключения */
    public static class CrptApiException extends RuntimeException {
        public enum ErrorCode {
            /** Ошибка подключения */
            CONNECTION_ERROR,
            /** Ошибка HTTP-статуса */
            STATUS_CODE_ERROR,
            /** Ошибка формата вывода */
            FORMAT_ERROR,
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

        @Override
        public String toString() {
            return "CrptApiException{" +
                    "statusCode=" + statusCode +
                    ", errorCode=" + errorCode +
                    '}';
        }
    }

    /** Конфигурация приложения */
    public static class AppConfig {
        /** Протокол хоста API */
        private String apiProtocol = "http";
        /** Хост API */
        private String apiHost = "localhost:3000";
        /** Лимит запросов */
        private int requestLimit = 100;
        /** Единица времени */
        private TimeUnit timeUnit = TimeUnit.SECONDS;

        public String getApiProtocol() {
            return apiProtocol;
        }

        public String getApiHost() {
            return apiHost;
        }

        public int getRequestLimit() {
            return requestLimit;
        }

        public TimeUnit getTimeUnit() {
            return timeUnit;
        }

        public void setApiProtocol(String apiProtocol) {
            this.apiProtocol = apiProtocol;
        }

        public void setApiHost(String apiHost) {
            this.apiHost = apiHost;
        }

        public void setRequestLimit(int requestLimit) {
            this.requestLimit = requestLimit;
        }

        public void setTimeUnit(TimeUnit timeUnit) {
            this.timeUnit = timeUnit;
        }
    }

    /**
     * Представление формата документа для ввода в оборот российского товара
     */
    public static class DocumentIntroductionFormat {
        public final String documentTypeFormat;
        public final String documentTypeRussianProduct;

        public DocumentIntroductionFormat(String documentTypeFormat, String documentTypeRussianProduct) {
            this.documentTypeFormat = documentTypeFormat;
            this.documentTypeRussianProduct = documentTypeRussianProduct;
        }

        @Override
        public String toString() {
            return "DocumentIntroductionFormat{" +
                    "documentTypeFormat='" + documentTypeFormat + '\'' +
                    ", documentTypeRussianProduct='" + documentTypeRussianProduct + '\'' +
                    '}';
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
        public final DocumentIntroductionFormat documentType;
        public final String productDocument;
        public final String productGroup;

        public DocumentRussianProductIntroduction(DocumentIntroductionFormat documentType, String productGroup, String productDocument) {
            this.documentType = documentType;
            this.productDocument = productDocument;
            this.productGroup = productGroup;
        }

        @Override
        public String toString() {
            return "DocumentRussianProductIntroduction{" +
                    "documentType=" + documentType +
                    ", productDocument='" + productDocument + '\'' +
                    ", productGroup='" + productGroup + '\'' +
                    '}';
        }
    }

    /**
     * Результат метода создания документа на ввод в оборот российского товара
     */
    public static final class DocumentId {
        public String value;
    }

    /**
     * Тело метода создания документа на ввод в оборот российского товара
     */
    private static class MethodPayload {
        public final String document_format;
        public final String product_document;
        public final String product_group;
        public final String type;
        public final String signature;

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
    static class CertKeyPayload {
        public String uuid;
        public String data;
    }

    static class TokenPayload {
        public String token;
    }

    /** Заданный период времени в миллисекундах */
    private final long periodMillis;

    /** HTTP-клиент */
    final HttpClient httpClient = HttpClient.newHttpClient();

    /** Конфигурация приложения */
    private final AppConfig appConfig;

    /** Маппер объектов */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Кэш*/
    private TokenCache cache;
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
    public CrptApi(AppConfig appConfig, CrptApi.TokenCache cache, CrptApi.CryptoSign cryptoSign, MeterRegistry meterRegistry) {
        this.metricsMap.put("createDocumentOfRussianProduct", new Metrics(meterRegistry, "createDocumentOfRussianProduct"));

        this.periodMillis = appConfig.timeUnit.toMillis(1);
        this.appConfig = appConfig;

        this.cache = cache;
        this.cryptoSign = cryptoSign;
    }

    /**
     * Вызов POST-метода API честного знака
     * @param <R> Тип возвращаемого значения
     * @param methodName Наименование метода
     * @param url URL метода API
     * @param payload Тело метода API в виде строки
     * @param returnType Тип возвращаемого значения
     */
    <D, R> R callPostApiWithMetrics(String methodName, String url, D payload, Class<R> returnType) throws InterruptedException {
        var metrics = metricsMap.get(methodName);

        try {
            return metrics.executionTimer.record(() -> callPostApi(methodName, url, payload, returnType, metrics));
        } catch (CompletionException e) {
            throw (InterruptedException) e.getCause();
        }
    }

     <D, R> R callPostApi(String methodName, String url, D payload, Class<R> returnType, Metrics metrics) {
        logger.info("{}: payload = {}", methodName, payload);
        try {

            var limiter = new BlockingTokenBucketRateLimiter(10, 10, periodMillis, TimeUnit.SECONDS);

            limiter.acquire();

            var payloadAsString = objectMapper.writeValueAsString(payload);

            var request = makeApiPostRequest(url, payloadAsString);

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 401) {
                logger.info("query with new token, {}: payload = {}", methodName, payload);
                cache.delete("token");
                var repeatedRequest = makeApiPostRequest(url, payloadAsString);
                response = httpClient.send(repeatedRequest, HttpResponse.BodyHandlers.ofString());
            }

            if (response.statusCode() >= 300) {
                throw new CrptApiException(response.statusCode());
            }

            var bodyString = response.body();

            var responseBody = objectMapper.readValue(bodyString, returnType);

            logger.info("success: {}: result = {}", methodName, responseBody);

            return responseBody;
        } catch (JacksonException e) {
            logger.error("error on method: {}, error: ", methodName, e);
            metrics.failureCounter.increment();
            throw new CrptApiException(e, CrptApiException.ErrorCode.FORMAT_ERROR);
        } catch (IOException e) {
            logger.error("error on method: {}, error: ", methodName, e);
            metrics.failureCounter.increment();
            throw new CrptApiException(e, CrptApiException.ErrorCode.IO_ERROR);
        } catch (InterruptedException e) {
            throw new CompletionException(e);
        }
    }

    private HttpRequest makeApiPostRequest(String url, String payload) {
        return HttpRequest.newBuilder()
                .uri(URI.create(String.format("%s://%s%s", appConfig.getApiProtocol(), appConfig.getApiHost(), url)))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + getToken())
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
    }

    /**
     * Создаёт документ для ввода в оборот товара, произведенного в РФ.
     *
     * @param document Объект документа.
     * @param signature Строка с открепленной подписью в формате base64.
     * @return объект `DocumentId`, содержащий статус ответа и тело ответа.
     */
    public DocumentId createDocumentOfRussianProduct(DocumentRussianProductIntroduction document, String signature) throws InterruptedException {
        var payload = preparePayload(document, signature);
        return callPostApiWithMetrics(
                "createDocumentOfRussianProduct",
                "/api/v3/lk/documents/create",
                payload,
                DocumentId.class
        );
    }

    /**
     * Получение токена из кэша, если токена не найден, то производится подпись нового токена
     */
    private String getToken() {
        return cache.get("token", (token) -> {
            CertKeyPayload response;
            try {
                response = fetchCertKey();
                response.data = cryptoSign.sign(response.data);
                var tokenPayload = fetchToken(response);

                return tokenPayload.token;
            } catch (IOException e) {
                throw new CrptApiException(e, CrptApiException.ErrorCode.IO_ERROR);
            } catch (InterruptedException e) {
                throw new CompletionException(e);
            }
        });
    }

    /**
     * Подготовка тело для метода создания документа
     */
    private MethodPayload preparePayload(DocumentRussianProductIntroduction document, String signature) {
        return new MethodPayload(
                document.documentType.documentTypeFormat,
                document.productDocument,
                document.productDocument,
                document.documentType.documentTypeRussianProduct,
                signature
        );
    }

    CertKeyPayload fetchCertKey() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(String.format("%s://%s/api/v3/auth/cert/key", appConfig.getApiProtocol(), appConfig.getApiHost())))
                .GET()
                .header("Accept", "application/json")
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 300) {
            throw new CrptApiException(response.statusCode());
        }

        var body = response.body();

        return objectMapper.readValue(body, CertKeyPayload.class);
    }

    TokenPayload fetchToken(CertKeyPayload cert) throws IOException, InterruptedException {
        var certAsString = objectMapper.writeValueAsString(cert);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(String.format("%s://%s/api/v3/auth/cert", appConfig.getApiProtocol(), appConfig.getApiHost())))
                .POST(HttpRequest.BodyPublishers.ofString(certAsString))
                .header("Accept", "application/json")
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 300) {
            throw new CrptApiException(response.statusCode());
        }

        var body = response.body();

        return objectMapper.readValue(body, TokenPayload.class);
    }
}
