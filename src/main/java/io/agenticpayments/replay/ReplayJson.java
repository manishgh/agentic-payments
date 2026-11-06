package io.agenticpayments.replay;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import io.agenticpayments.domain.PaymentRules;

final class ReplayJson {
    static final ObjectMapper JSON=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES).enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).configure(MapperFeature.ALLOW_COERCION_OF_SCALARS,false)
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY,true);
    private ReplayJson() {}
    static String write(Object value) { try{return JSON.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("Replay value cannot be encoded");} }
    static String digest(Object value) { return PaymentRules.sha256(write(value)); }
}
