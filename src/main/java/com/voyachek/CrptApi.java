package com.voyachek;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.TimeUnit;

public class CrptApi {

    private static final String API_PROTOCOL = "http";
    private static final String API_ADDRESS = "localhost:3000";

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

    /** Лимит запросов */
    private final int requestLimit;
    /** Заданный период времени в миллисекундах */
    private final long periodMillis;
    /** Время старта окна */
    private long windowStart;
    /** Текущее количество запросов */
    private int currentCount;

    /** HTTP-клиент */
    private final HttpClient httpClient;

    /**
     * @param timeUnit Промежуток времени
     * @param requestLimit Максимальное количество запросов в этом промежутке времени
     */
    public CrptApi(TimeUnit timeUnit, int requestLimit) {
        if (requestLimit <= 0) {
            throw new IllegalArgumentException("requestLimit должно быть положительным");
        }
        this.requestLimit = requestLimit;
        this.periodMillis = timeUnit.toMillis(1);
        this.windowStart = System.currentTimeMillis();
        this.currentCount = 0;
        this.httpClient = HttpClient.newHttpClient();
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
            if (currentCount < requestLimit) {
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
     * @param token Токен авторизации для доступа к API Честного знака.
     * @return объект `DocumentId`, содержащий статус ответа и тело ответа.
     */
    public DocumentId createDocumentOfRussianProduct(DocumentRussianProductIntroduction document, String signature, String token) {
        var payload = preparePayload(document, signature);

        return createDocument(payload, token);
    }

    /**
     * Подготовка тело для метода создания документа
     */
    private String preparePayload(DocumentRussianProductIntroduction document, String signature) {
        try {
            acquirePermit();
            var payload = new MethodPayload(
                    document.documentType.documentTypeFormat,
                    document.productDocument,
                    document.productDocument,
                    document.documentType.documentTypeRussianProduct,
                    signature
            );
            var mapper = new ObjectMapper();

            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Создание документа на основе тела
     */
    private DocumentId createDocument(String payload, String token) {
        try {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(String.format("%s://%s/api/v3/lk/documents/create", API_PROTOCOL, API_ADDRESS)))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            var body = response.body();

            return mapResponse(body);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Маппинг ответа от API в
     */
    private DocumentId mapResponse(String response) {
        try {
            var mapper = new ObjectMapper();

            return mapper.readValue(response, DocumentId.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
