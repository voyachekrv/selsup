package com.voyachek;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

class CacheImpl implements CrptApi.Cache {

    private final Map<String, String> cache = new HashMap<>();

    @Override
    public String get(String key, Function<String, String> value) {
        var val = this.cache.get(key);

        if (Objects.isNull(val)) {
            return value.apply(key);
        }

        return val;
    }

    @Override
    public void delete(String key) {
        cache.remove(key);
    }
}

class CryptoSignImpl implements CrptApi.CryptoSign {
    @Override
    public String sign(String value) {
        return Base64.getEncoder().encodeToString(UUID.randomUUID().toString().getBytes());
    }
}

public class Main {
    public static void main(String[] args) {
        var crptApi = new CrptApi(new CrptApi.AppConfig(), new CacheImpl(), new CryptoSignImpl(), new SimpleMeterRegistry());

        var document = crptApi.createDocumentOfRussianProduct(new CrptApi.DocumentRussianProductIntroduction(
                CrptApi.DocumentTypeRussianProductIntroduction.MANUAL,
                CrptApi.ProductGroup.MILK,
                "document"
        ), "abc");

        System.out.println(document.value);
    }
}