/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.pattern;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A positive and a negative (near-miss) sample for every default pattern. Tokens are assembled at runtime, so this
 * file contains no literal token that other secret scanners (e.g. GitHub push protection) would flag.
 */
class SecretPatternSamplesTest {

    /**
     * PEM boundary dashes, kept out of literal key headers.
     */
    private static final String DASHES = "-----";

    /**
     * Positive and negative sample per pattern name. Every default pattern must have an entry.
     */
    private static final Map<String, String[]> SAMPLES = Map.ofEntries(
        sample("AWS Access Key", "id=AKIA" + rep("Z2", 8), "id=AKIA" + rep("Z2", 7)),
        sample("AWS Secret Key", "aws_secret_key=" + rep("Ab1/", 10), "aws_secret_key=" + rep("Ab1/", 9)),
        sample("Google API Key", "key=AIza" + rep("a1", 17) + "b", "key=AIza" + rep("a1", 17)),
        sample("Firebase Key", "AAAA" + rep("a", 7) + ":" + rep("b", 140), "AAAA" + rep("a", 7) + ":" + rep("b", 139)),
        sample("Heroku API Key", "HEROKU_API_KEY=01234567-89ab-cdef-0123-456789abcdef",
            "HEROKU_API_KEY=not-a-uuid"),
        sample("Azure OpenAI Key", "azure.openai.api-key: " + rep("0a", 16), "azure.openai.api-key: " + rep("0a", 15)),
        sample("MongoDB Connection", "mongodb://admin:hunter2@db.example.com/app", "mongodb://db.example.com/app"),
        sample("PostgreSQL Connection", "postgresql://app:hunter2@db.example.com:5432/app",
            "postgresql://db.example.com:5432/app"),
        sample("MySQL Connection", "mysql://root:hunter2@localhost/app", "mysql://localhost/app"),
        sample("Redis Password", "redis://:hunter2@cache:6379", "redis://cache:6379"),
        sample("SQL Server Password", "mssql.datasource.password=Sup3rS3cret!", "mssql.datasource.password=short"),
        sample("OpenAI Project Key", "sk-proj-" + rep("Ab1", 16), "sk-proj-" + rep("Ab1", 15)),
        sample("OpenAI API Key", "sk-" + rep("Ab1", 16), "sk-" + rep("Ab1", 15)),
        sample("Anthropic API Key", "sk-ant-api03-" + rep("Ab1", 31), "sk-ant-api03-" + rep("Ab1", 30)),
        sample("HuggingFace Token", "hf_" + rep("Ab", 17), "hf_" + rep("Ab", 16)),
        sample("Replicate API Token", "r8_" + rep("Ab", 15), "r8_" + rep("Ab", 14)),
        sample("Cohere API Key", "cohere_api_key=" + rep("Ab", 20), "cohere_api_key=" + rep("Ab", 19)),
        sample("Mistral API Key", "mistral_api_key=" + rep("Ab", 16), "mistral_api_key=" + rep("Ab", 15)),
        sample("Groq API Key", "gsk_" + rep("Ab", 26), "gsk_" + rep("Ab", 25)),
        sample("Stripe Live Secret", "sk_live_" + rep("Ab", 12), "sk_live_" + rep("Ab", 11)),
        sample("Stripe Test Secret", "sk_test_" + rep("Ab", 12), "sk_test_" + rep("Ab", 11)),
        sample("GitHub Fine-Grained Token", "github_pat_" + rep("Ab", 41), "github_pat_" + rep("Ab", 40)),
        sample("GitHub Token", "ghp_" + rep("Ab", 18), "ghp_" + rep("Ab", 17)),
        sample("GitLab Token", "glpat-" + rep("Ab", 10), "glpat-" + rep("Ab", 9)),
        sample("Slack Token", "xoxb-" + rep("Ab", 10), "xoxb-" + rep("A", 9)),
        sample("Twilio API Key", "SK" + rep("0a", 16), "SK" + rep("0a", 15)),
        sample("Mailgun API Key", "key-" + rep("Ab", 16), "key-" + rep("Ab", 15)),
        sample("Slack Webhook", "https://hooks.slack.com/services/T" + rep("A", 8) + "/B" + rep("B", 8) + "/"
            + rep("Ab", 12), "https://hooks.slack.com/services/T" + rep("A", 8) + "/B" + rep("B", 8) + "/"
                + rep("Ab", 11)),
        sample("Discord Webhook", "https://discord.com/api/webhooks/" + rep("1", 18) + "/" + rep("Ab", 34),
            "https://discord.com/api/webhooks/" + rep("1", 18) + "/" + rep("Ab", 33)),
        sample("Private Key Block", pem("PRIVATE KEY"), pem("PUBLIC KEY")),
        sample("Basic Auth Header", "Authorization: Basic " + rep("QWxh", 5), "Authorization: Basic ${TOKEN}"),
        sample("JWT Token", "eyJ" + rep("a", 10) + "." + rep("b", 10) + "." + rep("c", 10), "eyJabc.def.ghi"),
        sample("Generic API Key Assignment", "api_key = " + rep("Ab1", 6), "api_key=${API_KEY}"),
        sample("Generic Password Assignment", "password=hunter22", "password=${DB_PASSWORD}"));

    @ParameterizedTest
    @MethodSource("patterns")
    void detects_positive_sample(final SecretPattern pattern) {
        assertThat(SAMPLES).as("missing samples for %s", pattern.getName()).containsKey(pattern.getName());
        final var positive = SAMPLES.get(pattern.getName())[0];

        assertThat(pattern.getPattern().matcher(positive).find()).as("%s in %s", pattern.getName(), positive).isTrue();
    }

    @ParameterizedTest
    @MethodSource("patterns")
    void ignores_negative_sample(final SecretPattern pattern) {
        assertThat(SAMPLES).as("missing samples for %s", pattern.getName()).containsKey(pattern.getName());
        final var negative = SAMPLES.get(pattern.getName())[1];

        assertThat(pattern.getPattern().matcher(negative).find()).as("%s in %s", pattern.getName(), negative).isFalse();
    }

    static Stream<Arguments> patterns() {
        return SecretPatternRegistry.DEFAULT_PATTERNS.stream()
            .map(pattern -> Arguments.argumentSet(pattern.getName(), pattern));
    }

    private static Map.Entry<String, String[]> sample(final String name, final String positive, final String negative) {
        return Map.entry(name, new String[] {
            positive, negative });
    }

    private static String rep(final String unit, final int count) {
        return unit.repeat(count);
    }

    private static String pem(final String label) {
        return DASHES + "BEGIN " + label + DASHES + "\nMIIBVQIBADANBgkqhkiG9w0BAQEFAASC\n" + DASHES + "END " + label
            + DASHES;
    }
}
