/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.impl.verifier;

import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.apache.camel.CamelContext;
import org.apache.camel.ComponentVerifier;
import org.apache.camel.NoSuchOptionException;
import org.apache.camel.TypeConverter;
import org.apache.camel.catalog.EndpointValidationResult;
import org.apache.camel.catalog.RuntimeCamelCatalog;
import org.apache.camel.util.CamelContextHelper;
import org.apache.camel.util.EndpointHelper;
import org.apache.camel.util.IntrospectionSupport;

import static org.apache.camel.util.StreamUtils.stream;

public class DefaultComponentVerifier implements ComponentVerifier {
    private final String defaultScheme;
    private final CamelContext camelContext;

    public DefaultComponentVerifier(String defaultScheme, CamelContext camelContext) {
        this.defaultScheme = defaultScheme;
        this.camelContext = camelContext;
    }

    // *************************************
    //
    // *************************************

    @Override
    public Result verify(Scope scope, Map<String, Object> parameters) {
        // Camel context is mandatory
        if (this.camelContext == null) {
            return ResultBuilder.withStatusAndScope(Result.Status.ERROR, scope)
                .error(ResultErrorBuilder.withCodeAndDescription(VerificationError.StandardCode.INTERNAL, "Missing camel-context").build())
                .build();
        }

        if (scope == Scope.PARAMETERS) {
            return verifyParameters(parameters);
        }
        if (scope == Scope.CONNECTIVITY) {
            return verifyConnectivity(parameters);
        }

        throw new IllegalArgumentException("Unsupported Verifier scope: " + scope);
    }

    protected Result verifyConnectivity(Map<String, Object> parameters) {
        return ResultBuilder.withStatusAndScope(Result.Status.UNSUPPORTED, Scope.CONNECTIVITY).build();
    }

    protected Result verifyParameters(Map<String, Object> parameters) {
        ResultBuilder builder = ResultBuilder.withStatusAndScope(Result.Status.OK, Scope.PARAMETERS);

        // Validate against catalog
        verifyParametersAgainstCatalog(builder, parameters);

        return builder.build();
    }

    // *************************************
    // Helpers :: Parameters validation
    // *************************************

    protected void verifyParametersAgainstCatalog(ResultBuilder builder, Map<String, Object> parameters) {
        String scheme = defaultScheme;
        if (parameters.containsKey("scheme")) {
            scheme = parameters.get("scheme").toString();
        }

        // Grab the runtime catalog to check parameters
        RuntimeCamelCatalog catalog = camelContext.getRuntimeCamelCatalog();

        // Convert from Map<String, Object> to  Map<String, String> as required
        // by the Camel Catalog
        EndpointValidationResult result = catalog.validateProperties(
            scheme,
            parameters.entrySet().stream()
                .collect(
                    Collectors.toMap(
                        Map.Entry::getKey,
                        e -> camelContext.getTypeConverter().convertTo(String.class, e.getValue())
                    )
                )
        );

        if (!result.isSuccess()) {
            stream(result.getUnknown())
                .map(option -> ResultErrorBuilder.withUnknownOption(option).build())
                .forEach(builder::error);
            stream(result.getRequired())
                .map(option -> ResultErrorBuilder.withMissingOption(option).build())
                .forEach(builder::error);
            stream(result.getInvalidBoolean())
                .map(entry -> ResultErrorBuilder.withIllegalOption(entry.getKey(), entry.getValue()).build())
                .forEach(builder::error);
            stream(result.getInvalidInteger())
                .map(entry -> ResultErrorBuilder.withIllegalOption(entry.getKey(), entry.getValue()).build())
                .forEach(builder::error);
            stream(result.getInvalidNumber())
                .map(entry -> ResultErrorBuilder.withIllegalOption(entry.getKey(), entry.getValue()).build())
                .forEach(builder::error);
            stream(result.getInvalidEnum())
                .map(entry ->
                    ResultErrorBuilder.withIllegalOption(entry.getKey(), entry.getValue())
                        .detail("enum.values", result.getEnumChoices(entry.getKey()))
                        .build())
                .forEach(builder::error);
        }
    }

    // *************************************
    // Helpers
    // *************************************

    protected CamelContext getCamelContext() {
        return camelContext;
    }

    protected <T> T setProperties(T instance, Map<String, Object> properties) throws Exception {
        if (camelContext == null) {
            throw new IllegalStateException("Camel context is null");
        }

        if (!properties.isEmpty()) {
            final TypeConverter converter = camelContext.getTypeConverter();

            IntrospectionSupport.setProperties(converter, instance, properties);

            for (Map.Entry<String, Object> entry : properties.entrySet()) {
                if (entry.getValue() instanceof String) {
                    String value = (String)entry.getValue();
                    if (EndpointHelper.isReferenceParameter(value)) {
                        IntrospectionSupport.setProperty(camelContext, converter, instance, entry.getKey(), null, value, true);
                    }
                }
            }
        }

        return instance;
    }

    protected <T> T setProperties(T instance, String prefix, Map<String, Object> properties) throws Exception {
        return setProperties(
            instance,
            IntrospectionSupport.extractProperties(properties, prefix, false)
        );
    }

    protected <T> Optional<T> getOption(Map<String, Object> parameters, String key, Class<T> type) {
        Object value = parameters.get(key);
        if (value != null) {
            return Optional.ofNullable(CamelContextHelper.convertTo(camelContext, type, value));
        }

        return Optional.empty();
    }

    protected <T> T getOption(Map<String, Object> parameters, String key, Class<T> type, Supplier<T> defaultSupplier) {
        return getOption(parameters, key, type).orElseGet(defaultSupplier);
    }

    protected <T> T getMandatoryOption(Map<String, Object> parameters, String key, Class<T> type) throws NoSuchOptionException {
        return getOption(parameters, key, type).orElseThrow(() ->  new NoSuchOptionException(key));
    }
}
