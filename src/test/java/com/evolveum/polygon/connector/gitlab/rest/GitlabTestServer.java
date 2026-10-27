/*
 * Copyright 2026 easyIGA contributors
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.evolveum.polygon.connector.gitlab.rest;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

final class GitlabTestServer implements AutoCloseable {

    private final HttpServer server;
    private final Deque<StubResponse> responses = new ArrayDeque<>();
    private final List<RecordedRequest> requests = new ArrayList<>();

    GitlabTestServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    synchronized void enqueue(int status, String body) {
        enqueue(status, body, Map.of());
    }

    synchronized void enqueue(int status, String body, Map<String, String> headers) {
        responses.addLast(new StubResponse(status, body, headers));
    }

    synchronized List<RecordedRequest> requests() {
        return List.copyOf(requests);
    }

    synchronized RecordedRequest request(int index) {
        return requests.get(index);
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] requestBody = exchange.getRequestBody().readAllBytes();
        StubResponse response;
        synchronized (this) {
            requests.add(new RecordedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getRawPath(),
                    exchange.getRequestURI().getRawQuery(),
                    new String(requestBody, StandardCharsets.UTF_8),
                    copyHeaders(exchange)));
            response = responses.pollFirst();
        }

        if (response == null) {
            response = new StubResponse(500, "{\"message\":\"unexpected request\"}", Map.of());
        }
        response.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
        byte[] responseBody = response.body().getBytes(StandardCharsets.UTF_8);
        if (response.status() == 204) {
            exchange.sendResponseHeaders(response.status(), -1);
        } else {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(response.status(), responseBody.length);
            exchange.getResponseBody().write(responseBody);
        }
        exchange.close();
    }

    private static Map<String, List<String>> copyHeaders(HttpExchange exchange) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> result.put(name, List.copyOf(values)));
        return result;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    record RecordedRequest(
            String method,
            String rawPath,
            String rawQuery,
            String body,
            Map<String, List<String>> headers) {

        String firstHeader(String name) {
            return headers.entrySet().stream()
                    .filter(entry -> name.equalsIgnoreCase(entry.getKey()))
                    .flatMap(entry -> entry.getValue().stream())
                    .findFirst()
                    .orElse(null);
        }
    }

    private record StubResponse(int status, String body, Map<String, String> headers) {
    }
}
