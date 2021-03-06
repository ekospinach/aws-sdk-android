/*
 * Copyright 2010-2015 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.amazonaws.util;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.Request;
import com.amazonaws.http.HttpMethodName;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HttpUtils {

    private static final String DEFAULT_ENCODING = "UTF-8";

    /**
     * Regex which matches any of the sequences that we need to fix up after
     * URLEncoder.encode().
     */
    private static final Pattern ENCODED_CHARACTERS_PATTERN;
    static {
        StringBuilder pattern = new StringBuilder();

        pattern
                .append(Pattern.quote("+"))
                .append("|")
                .append(Pattern.quote("*"))
                .append("|")
                .append(Pattern.quote("%7E"))
                .append("|")
                .append(Pattern.quote("%2F"));

        ENCODED_CHARACTERS_PATTERN = Pattern.compile(pattern.toString());
    }

    /**
     * Encode a string for use in the path of a URL; uses URLEncoder.encode,
     * (which encodes a string for use in the query portion of a URL), then
     * applies some postfilters to fix things up per the RFC. Can optionally
     * handle strings which are meant to encode a path (ie include '/'es which
     * should NOT be escaped).
     *
     * @param value the value to encode
     * @param path true if the value is intended to represent a path
     * @return the encoded value
     */
    public static String urlEncode(final String value, final boolean path) {
        if (value == null) {
            return "";
        }

        try {
            String encoded = URLEncoder.encode(value, DEFAULT_ENCODING);

            Matcher matcher = ENCODED_CHARACTERS_PATTERN.matcher(encoded);
            StringBuffer buffer = new StringBuffer(encoded.length());

            while (matcher.find()) {
                String replacement = matcher.group(0);

                if ("+".equals(replacement)) {
                    replacement = "%20";
                } else if ("*".equals(replacement)) {
                    replacement = "%2A";
                } else if ("%7E".equals(replacement)) {
                    replacement = "~";
                } else if (path && "%2F".equals(replacement)) {
                    replacement = "/";
                }

                matcher.appendReplacement(buffer, replacement);
            }

            matcher.appendTail(buffer);
            return buffer.toString();

        } catch (UnsupportedEncodingException ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * Returns true if the specified URI is using a non-standard port (i.e. any
     * port other than 80 for HTTP URIs or any port other than 443 for HTTPS
     * URIs).
     *
     * @param uri
     * @return True if the specified URI is using a non-standard port, otherwise
     *         false.
     */
    public static boolean isUsingNonDefaultPort(URI uri) {
        String scheme = uri.getScheme().toLowerCase();
        int port = uri.getPort();

        if (port <= 0)
            return false;
        if (scheme.equals("http") && port == 80)
            return false;
        if (scheme.equals("https") && port == 443)
            return false;

        return true;
    }

    public static boolean usePayloadForQueryParameters(Request<?> request) {
        boolean requestIsPOST = HttpMethodName.POST.equals(request.getHttpMethod());
        boolean requestHasNoPayload = (request.getContent() == null);

        return requestIsPOST && requestHasNoPayload;
    }

    /**
     * Creates an encoded query string from all the parameters in the specified
     * request.
     *
     * @param request The request containing the parameters to encode.
     * @return Null if no parameters were present, otherwise the encoded query
     *         string for the parameters present in the specified request.
     */
    public static String encodeParameters(Request<?> request) {
        if (request.getParameters().isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        try {
            for (Entry<String, String> entry : request.getParameters().entrySet()) {
                String encodedName = URLEncoder.encode(entry.getKey(), DEFAULT_ENCODING);
                String value = entry.getValue();
                String encodedValue = value == null ? "" : URLEncoder.encode(value,
                        DEFAULT_ENCODING);
                if (!first) {
                    sb.append("&");
                } else {
                    first = false;
                }
                sb.append(encodedName).append("=").append(encodedValue);
            }
        } catch (UnsupportedEncodingException e) {
            throw new IllegalArgumentException(e);
        }
        return sb.toString();
    }

    /**
     * Append the given path to the given baseUri. By default, all slash
     * characters in path will not be url-encoded.
     */
    public static String appendUri(String baseUri, String path) {
        return appendUri(baseUri, path, false);
    }

    /**
     * Append the given path to the given baseUri.
     * <p>
     * This method will encode the given path but not the given baseUri.
     * </p>
     *
     * @param baseUri The URI to append to (required, may be relative)
     * @param path The path to append (may be null or empty)
     * @param escapeDoubleSlash Whether double-slash in the path should be
     *            escaped to "/%2F"
     * @return The baseUri with the (encoded) path appended
     */
    public static String appendUri(final String baseUri, String path,
            final boolean escapeDoubleSlash) {
        String resultUri = baseUri;
        if (path != null && path.length() > 0) {
            if (path.startsWith("/")) {
                // trim the trailing slash in baseUri, since the path already
                // starts with a slash
                if (resultUri.endsWith("/")) {
                    resultUri = resultUri.substring(0, resultUri.length() - 1);
                }
            } else if (!resultUri.endsWith("/")) {
                resultUri += "/";
            }
            String encodedPath = HttpUtils.urlEncode(path, true);
            if (escapeDoubleSlash) {
                encodedPath = encodedPath.replace("//", "/%2F");
            }
            resultUri += encodedPath;
        } else if (!resultUri.endsWith("/")) {
            resultUri += "/";
        }

        return resultUri;
    }

    /**
     * Fetches a file from the URI given and returns an input stream to it.
     *
     * @param uri the uri of the file to fetch
     * @param config optional configuration overrides
     * @return an InputStream containing the retrieved data
     * @throws IOException on error
     */
    public static InputStream fetchFile(
            final URI uri,
            final ClientConfiguration config) throws IOException {
        URL url = uri.toURL();
        // TODO: support proxy?
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(getConnectionTimeout(config));
        connection.setReadTimeout(getSocketTimeout(config));
        connection.addRequestProperty("User-Agent", getUserAgent(config));

        if (connection.getResponseCode() != 200) {
            InputStream is = connection.getErrorStream();
            if (is != null) {
                is.close();
            }
            connection.disconnect();
            throw new IOException("Error fetching file from " + uri + ": "
                    + connection.getResponseMessage());
        }

        return connection.getInputStream();
    }

    private static String getUserAgent(final ClientConfiguration config) {
        String userAgent = null;

        if (config != null) {
            userAgent = config.getUserAgent();
        }

        if (userAgent == null) {
            userAgent = ClientConfiguration.DEFAULT_USER_AGENT;
        } else if (!ClientConfiguration.DEFAULT_USER_AGENT.equals(userAgent)) {
            userAgent += ", " + ClientConfiguration.DEFAULT_USER_AGENT;
        }

        return userAgent;
    }

    private static int getConnectionTimeout(final ClientConfiguration config) {
        if (config != null) {
            return config.getConnectionTimeout();
        }
        return ClientConfiguration.DEFAULT_CONNECTION_TIMEOUT;
    }

    private static int getSocketTimeout(final ClientConfiguration config) {
        if (config != null) {
            return config.getSocketTimeout();
        }
        return ClientConfiguration.DEFAULT_SOCKET_TIMEOUT;
    }
}
