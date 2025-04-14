package com.voyachek;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

class CacheImpl implements CrptApi.TokenCache {

    private final Cache<String, String> cache;

    public CacheImpl() {
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(10, TimeUnit.HOURS)
                .maximumSize(100)
                .build();
    }

    @Override
    public String get(String key, Function<String, String> valueLoader) {
        return cache.get(key, valueLoader);
    }

    @Override
    public void delete(String key) {
        cache.invalidate(key);
    }
}

class CryptoSignFake implements CrptApi.CryptoSign {
    @Override
    public String sign(String value) {
        return Base64.getEncoder().encodeToString(UUID.randomUUID().toString().getBytes());
    }
}

public class Main {
    public static void main(String[] args) {
        try {
            var crptApi = new CrptApi(new CrptApi.AppConfig(), new CacheImpl(), new CryptoSignFake(), new SimpleMeterRegistry());

            var document = crptApi.createDocumentOfRussianProduct(new CrptApi.DocumentRussianProductIntroduction(
                    CrptApi.DocumentTypeRussianProductIntroduction.MANUAL,
                    CrptApi.ProductGroup.MILK,
                    "document"
            ), "abc");

            System.out.println(document.value);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}