package com.daml.ledger.javaapi.data;

import com.digitalasset.ledger.api.v1.ValueOuterClass;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Objects;
import java.util.Optional;

public final class Enum extends Value {

    private final Optional<Identifier> enumId;

    private final String constructor;

    public Enum(@NonNull Identifier enumId, @NonNull String constructor){
        this.enumId = Optional.of(enumId);
        this.constructor = constructor;
    }

    public Enum(@NonNull String constructor){
        this.enumId = Optional.empty();
        this.constructor = constructor;
    }

    public static Enum fromProto(ValueOuterClass.Enum value) {
        String constructor = value.getConstructor();
        if (value.hasEnumId()) {
            Identifier variantId = Identifier.fromProto(value.getEnumId());
            return new Enum(variantId, constructor);
        } else {
            return new Enum(constructor);
        }
    }

    @NonNull
    public Optional<Identifier> getEnumId() {
        return enumId;
    }

    @NonNull
    public String getConstructor() {
        return constructor;
    }


    @Override
    public ValueOuterClass.Value toProto() {
        return ValueOuterClass.Value.newBuilder().setEnum(this.toProtoEnum()).build();
    }

    public ValueOuterClass.Enum toProtoEnum() {
        ValueOuterClass.Enum.Builder builder = ValueOuterClass.Enum.newBuilder();
        builder.setConstructor(this.getConstructor());
        this.getEnumId().ifPresent(identifier -> builder.setEnumId(identifier.toProto()));
        return builder.build();
    }


    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Enum value = (Enum) o;
        return Objects.equals(enumId, value.enumId) && Objects.equals(constructor, value.constructor);
    }

    @Override
    public int hashCode() {
        return Objects.hash(enumId, constructor);
    }

    @Override
    public String toString() {
        return "Enum{" + "variantId=" + enumId + ", constructor='" + constructor + "'}";
    }

}
