package com.pawwork.opencode.demo.tools;

/**
 * A dependency-free arithmetic evaluator for the {@code calculate} tool.
 *
 * <p>Supports {@code + - * / % ^}, parentheses, unary signs and decimals — deliberately nothing
 * else, so a model-supplied expression can never execute code.</p>
 */
final class Arithmetic {

    private final String source;
    private int position;

    private Arithmetic(String source) {
        this.source = source;
    }

    static double evaluate(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("expression is empty");
        }
        Arithmetic parser = new Arithmetic(expression);
        double value = parser.parseExpression();
        parser.skipWhitespace();
        if (parser.position < parser.source.length()) {
            throw new IllegalArgumentException("unexpected '"
                    + parser.source.charAt(parser.position) + "' at position " + parser.position);
        }
        return value;
    }

    private double parseExpression() {
        double value = parseTerm();
        while (true) {
            skipWhitespace();
            char c = peek();
            if (c == '+') {
                position++;
                value += parseTerm();
            } else if (c == '-') {
                position++;
                value -= parseTerm();
            } else {
                return value;
            }
        }
    }

    private double parseTerm() {
        double value = parseUnary();
        while (true) {
            skipWhitespace();
            char c = peek();
            if (c == '*') {
                position++;
                value *= parseUnary();
            } else if (c == '/') {
                position++;
                value /= parseUnary();
            } else if (c == '%') {
                position++;
                value %= parseUnary();
            } else {
                return value;
            }
        }
    }

    private double parseUnary() {
        skipWhitespace();
        char c = peek();
        if (c == '-') {
            position++;
            return -parseUnary();
        }
        if (c == '+') {
            position++;
            return parseUnary();
        }
        return parsePower();
    }

    private double parsePower() {
        double base = parsePrimary();
        skipWhitespace();
        if (peek() == '^') {
            position++;
            return Math.pow(base, parseUnary());
        }
        return base;
    }

    private double parsePrimary() {
        skipWhitespace();
        char c = peek();
        if (c == '(') {
            position++;
            double value = parseExpression();
            skipWhitespace();
            if (peek() != ')') {
                throw new IllegalArgumentException("missing ')' at position " + position);
            }
            position++;
            return value;
        }
        int start = position;
        while (position < source.length()
                && (Character.isDigit(source.charAt(position)) || source.charAt(position) == '.')) {
            position++;
        }
        if (start == position) {
            throw new IllegalArgumentException("expected a number at position " + position);
        }
        try {
            return Double.parseDouble(source.substring(start, position));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + source.substring(start, position) + "' is not a number");
        }
    }

    private char peek() {
        return position < source.length() ? source.charAt(position) : '\0';
    }

    private void skipWhitespace() {
        while (position < source.length() && Character.isWhitespace(source.charAt(position))) {
            position++;
        }
    }
}
