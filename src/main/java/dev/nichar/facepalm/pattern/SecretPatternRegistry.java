/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.pattern;

import java.util.List;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;

/**
 * Central registry for built-in secret detection patterns.
 * Defines "Gold Standard" signatures for cloud providers, databases, and AI platforms.
 *
 * @author Nikolas Charalambidis
 * @since 1.0.0
 */
@UtilityClass
public class SecretPatternRegistry {

    /**
     * {@code user:password} part of a database connection URI. Both parts are bounded and exclude the delimiters
     * ({@code :} in the user, {@code @} in both), so a long line without {@code @} is rejected in linear time instead
     * of
     * backtracking quadratically.
     */
    private static final String DB_CREDENTIALS = "[^/\\s:@]{1,256}:[^/\\s@]{1,256}";

    /**
     * Up to three separated segments after a keyword, so names such as {@code STRIPE_SECRET_KEY},
     * {@code AWS_SECRET_ACCESS_KEY} or {@code SENDGRID_API_KEY_ID} match. Segments that describe metadata of a
     * secret ({@code SECRET_NAME}, {@code token.ttl}) do not.
     */
    private static final String KEYWORD_SUFFIX = "(?:[_.-](?!(?:name|path|file|type|url|uri|header|ttl|expiry|expires|"
        + "timeout|validity|length|prefix|ref|location|enabled)(?![A-Za-z0-9]))[A-Za-z0-9]+){0,3}";

    /**
     * Argument that names a secret instead of containing one: {@code UPPER_SNAKE} with at least one underscore
     * ({@code GITHUB_TOKEN}), a dotted key ({@code spring.datasource.password}) or a camelCase identifier of letters
     * only ({@code userDetails}). Random secrets mix letters and digits and match none of these.
     */
    private static final String NAME_ARGUMENT = "(?:[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+|[a-z][a-z0-9-]*(?:\\.[a-z][a-z0-9-]*)+|[a-z]+(?:[A-Z][a-z]+)*)";

    /**
     * Method or function name, optionally qualified ({@code getenv}, {@code env.getProperty}, {@code $svc.issue}).
     */
    private static final String CALLEE = "[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*";

    /**
     * Call whose arguments are only names: {@code getSecret()}, {@code env.getProperty(STRIPE_WEBHOOK_SECRET)},
     * {@code jwt.generateToken(userDetails)}, with one level of nesting
     * ({@code Objects.requireNonNull(System.getenv(GITHUB_TOKEN))}). Such code reads a secret instead of containing
     * one. A call with a literal secret argument ({@code wrap("Zq8vLm3N...")}) is not matched and stays detected. The
     * extractor strips quotes before matching, so quoted names look like the forms above.
     */
    private static final String METHOD_CALL;

    static {
        final var names = "(?:" + NAME_ARGUMENT + "(?:\\s*,\\s*" + NAME_ARGUMENT + ")*)?";
        final var argument = "(?:" + CALLEE + "\\(\\s*" + names + "\\s*\\)|" + NAME_ARGUMENT + ")";
        METHOD_CALL = CALLEE + "\\(\\s*(?:" + argument + "(?:\\s*,\\s*" + argument + ")*)?\\s*\\)";
    }

    /**
     * Immutable list of default patterns used by the scanner.
     * Gaps between a keyword and the secret are bounded (e.g. {@code .{0,100}?}), never {@code .*}: an unbounded gap
     * makes matching quadratic on long lines such as minified JavaScript.
     * Ordered from high-confidence specific signatures to generic fallback patterns.
     */
    public static final List<SecretPattern> DEFAULT_PATTERNS = List.of(
        // Cloud & Infrastructure Providers
        new SecretPattern("AWS Access Key", Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"), 70, 95, false),
        new SecretPattern("AWS Secret Key", Pattern.compile(
            "(?i)aws_(?:secret|access|key)[\\w.-]{0,30}\\s*[=:]\\s*([^\\s]{40})"),
            85, 80, false),
        new SecretPattern("Google API Key", Pattern.compile("\\bAIza[0-9A-Za-z\\-_]{35}\\b"), 60, 90, false),
        new SecretPattern("Firebase Key", Pattern.compile("AAAA[A-Za-z0-9_\\-]{7}:[A-Za-z0-9_\\-]{140,}"), 75, 95,
            false),
        new SecretPattern("Heroku API Key", Pattern.compile(
            "(?i)heroku.{0,100}?[=:]\\s*([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})"),
            70,
            85, false),
        new SecretPattern("Azure OpenAI Key", Pattern.compile(
            "(?i)(?:azure|openai).{0,100}?key.{0,10}[=:]\\s*([a-zA-Z0-9]{32})"), 80, 85, false),

        // Database Connection Strings
        new SecretPattern("MongoDB Connection", Pattern.compile("(?i)mongodb(?:\\+srv)?://(" + DB_CREDENTIALS + ")@"),
            90,
            95, false),
        new SecretPattern("PostgreSQL Connection", Pattern.compile("(?i)postgres(?:ql)?://(" + DB_CREDENTIALS + ")@"),
            90,
            95, false),
        new SecretPattern("MySQL Connection", Pattern.compile("(?i)mysql://(" + DB_CREDENTIALS + ")@"), 90, 95, false),
        new SecretPattern("Redis Password", Pattern.compile("(?i)redis://[^/\\s:@]{0,256}:([^/\\s@]{1,256})@"), 80, 90,
            false),
        new SecretPattern("SQL Server Password", Pattern.compile(
            "(?i)(?:sql[_-]?server|mssql).{0,100}?password\\s*[=:]\\s*([^\\s;]{8,})"), 80, 85, false),

        // AI & LLM Providers (High Risk)
        new SecretPattern("OpenAI Project Key", Pattern.compile("sk-proj-[A-Za-z0-9]{48,}"), 90, 100, false),
        new SecretPattern("OpenAI API Key", Pattern.compile("\\bsk-[A-Za-z0-9]{48}\\b"), 80, 90, false),
        new SecretPattern("Anthropic API Key", Pattern.compile("sk-ant-api03-[A-Za-z0-9_\\-]{93,95}"), 85, 100, false),
        new SecretPattern("HuggingFace Token", Pattern.compile("hf_[A-Za-z0-9]{34}"), 75, 95, false),
        new SecretPattern("Replicate API Token", Pattern.compile("r8_[A-Za-z0-9]{30,40}"), 75, 95, false),
        new SecretPattern("Cohere API Key", Pattern.compile(
            "\\b[cC]ohere[_-]?api[_-]?key.{0,10}[=:]\\s*([A-Za-z0-9]{40})"), 75, 90, false),
        new SecretPattern("Mistral API Key", Pattern.compile(
            "\\b[mM]istral[_-]?api[_-]?key.{0,10}[=:]\\s*([A-Za-z0-9]{32})"), 75, 90, false),
        new SecretPattern("Groq API Key", Pattern.compile("gsk_[A-Za-z0-9]{52}"), 80, 95, false),

        // Payments & Finance
        new SecretPattern("Stripe Live Secret", Pattern.compile("sk_live_[0-9a-zA-Z]{24,}"), 85, 95, false),
        new SecretPattern("Stripe Test Secret", Pattern.compile("sk_test_[0-9a-zA-Z]{24,}"), 30, 95, false),

        // VCS & Collaboration
        new SecretPattern("GitHub Fine-Grained Token", Pattern.compile("github_pat_[0-9A-Za-z_]{82,}"), 85, 100, false),
        new SecretPattern("GitHub Token", Pattern.compile("ghp_[0-9A-Za-z]{36}"), 80, 100, false),
        new SecretPattern("GitLab Token", Pattern.compile("glpat-[0-9a-zA-Z\\-_]{20}"), 85, 100, false),
        new SecretPattern("Slack Token", Pattern.compile("xox[baprs]-[0-9a-zA-Z-]{10,48}"), 75, 95, false),
        new SecretPattern("Twilio API Key", Pattern.compile("SK[0-9a-fA-F]{32}"), 70, 90, false),
        new SecretPattern("Mailgun API Key", Pattern.compile("key-[0-9a-zA-Z]{32}"), 70, 90, false),

        // Webhooks
        new SecretPattern("Slack Webhook", Pattern.compile(
            "https://hooks\\.slack\\.com/services/T[A-Z0-9]{8}/B[A-Z0-9]{8}/[A-Za-z0-9]{24}"), 80, 100, false),
        new SecretPattern("Discord Webhook", Pattern.compile(
            "https://discord\\.com/api/webhooks/[0-9]{18,19}/[A-Za-z0-9\\-_]{68}"), 70, 100, false),

        // Cryptographic Keys
        // Any PEM/PGP private key label (PKCS#8, ENCRYPTED, RSA, EC, DSA, OPENSSH, PGP ... BLOCK); END must match
        // BEGIN.
        new SecretPattern("Private Key Block", Pattern.compile(
            "(?s)-----BEGIN ((?:[A-Z0-9]+ )*PRIVATE KEY(?: BLOCK)?)-----.*?-----END \\1-----"),
            100, 100, true),

        // Generic & Fallback Patterns
        new SecretPattern("Basic Auth Header", Pattern.compile(
            "(?i)Authorization:\\s*Basic\\s+(?!\\{\\{|\\$\\{)([A-Za-z0-9+/=]{20,})"), 60, 75, false),
        new SecretPattern("JWT Token", Pattern.compile(
            "eyJ[A-Za-z0-9_\\-]{10,}\\.[A-Za-z0-9._\\-]{10,}\\.[A-Za-z0-9._\\-]{10,}"), 30, 40, false),
        new SecretPattern("Generic API Key Assignment", Pattern.compile(
            "(?i)(?:api[_-]?key|secret|token|client[_-]?secret)" + KEYWORD_SUFFIX
                + "\\s*[=:]\\s*['\"]?(?!\\{\\{|\\$\\{|" + METHOD_CALL
                + ")([^'\"\\s}]{16,})['\"]?"),
            65, 70, false),
        new SecretPattern("Generic Password Assignment", Pattern.compile(
            "(?i)(?:password|passwd|pwd|(?<![a-z])pass(?:phrase)?)\\s*[=:]\\s*['\"]?(?!\\{\\{|\\$\\{|" + METHOD_CALL
                + ")([^'\"\\s}]{8,})['\"]?"),
            50, 40, false));
}
