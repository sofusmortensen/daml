// Copyright (c) 2019 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.lf_latest;

import com.daml.ledger.javaapi.data.Enum;
import com.digitalasset.ledger.api.v1.ValueOuterClass;
import org.junit.jupiter.api.Test;
import org.junit.platform.runner.JUnitPlatform;
import org.junit.runner.RunWith;
import tests.enumtest.EnumColor;

import static org.junit.jupiter.api.Assertions.assertEquals;

@RunWith(JUnitPlatform.class)
public class EnumTest {

    @Test
    public void enumRoundTrip() {

        ValueOuterClass.Enum protoEnum =
                ValueOuterClass.Enum.newBuilder()
                .setConstructor("Red")
                .build();

        Enum anEnum = Enum.fromProto(protoEnum);
        EnumColor fromValue = EnumColor.fromValue(anEnum);
        EnumColor fromConstructor = EnumColor.Red;
        EnumColor fromRoundtrip = EnumColor.fromValue(fromConstructor.toValue());

        assertEquals(fromValue, fromConstructor);
        assertEquals(fromConstructor.toValue(), anEnum);
        assertEquals(fromConstructor, fromRoundtrip);
    }

}