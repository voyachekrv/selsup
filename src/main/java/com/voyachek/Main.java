package com.voyachek;

import java.util.concurrent.TimeUnit;

public class Main {
    public static void main(String[] args) {
        var crptApi = new CrptApi(TimeUnit.SECONDS, 100);

        var document = crptApi.createDocumentOfRussianProduct(new CrptApi.DocumentRussianProductIntroduction(
                CrptApi.DocumentTypeRussianProductIntroduction.MANUAL,
                CrptApi.ProductGroup.MILK,
                "document"
        ), "abc", "token-abc");

        System.out.println(document.value);
    }
}