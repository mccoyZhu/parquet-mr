/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.parquet.internal.column.columnindex;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

import org.apache.parquet.io.api.Binary;
import org.apache.parquet.schema.OriginalType;
import org.apache.parquet.schema.PrimitiveType;

/**
 * Class for truncating min/max values for binary types.
 */
abstract class BinaryTruncator {
  enum Validity {
    VALID, MALFORMED, UNMAPPABLE;
  }

  private static class CharsetValidator {
    private final CharBuffer dummyBuffer = CharBuffer.allocate(1024);
    private final CharsetDecoder decoder;

    CharsetValidator(Charset charset) {
      decoder = charset.newDecoder();
      decoder.onMalformedInput(CodingErrorAction.REPORT);
      decoder.onUnmappableCharacter(CodingErrorAction.REPORT);
    }

    Validity checkValidity(ByteBuffer buffer) {
      int pos = buffer.position();
      CoderResult result = CoderResult.OVERFLOW;
      while (result.isOverflow()) {
        dummyBuffer.clear();
        result = decoder.decode(buffer, dummyBuffer, true);
      }
      buffer.position(pos);
      if (result.isUnderflow()) {
        return Validity.VALID;
      } else if (result.isMalformed()) {
        return Validity.MALFORMED;
      } else {
        return Validity.UNMAPPABLE;
      }
    }
  }

  private static final BinaryTruncator NO_OP_TRUNCATOR = new BinaryTruncator() {
    @Override
    Binary truncateMin(Binary minValue, int length) {
      return minValue;
    }

    @Override
    Binary truncateMax(Binary maxValue, int length) {
      return maxValue;
    }
  };

  private static final BinaryTruncator DEFAULT_UTF8_TRUNCATOR = new BinaryTruncator() {
    private final CharsetValidator validator = new CharsetValidator(StandardCharsets.UTF_8);

    @Override
    Binary truncateMin(Binary minValue, int length) {
      if (minValue.length() <= length) {
        return minValue;
      }
      ByteBuffer buffer = minValue.toByteBuffer();
      byte[] array;
      if (validator.checkValidity(buffer) == Validity.VALID) {
        array = truncateUtf8(buffer, length);
      } else {
        array = truncate(buffer, length);
      }
      return array == null ? minValue : Binary.fromConstantByteArray(array);
    }

    @Override
    Binary truncateMax(Binary maxValue, int length) {
      if (maxValue.length() <= length) {
        return maxValue;
      }
      byte[] array;
      ByteBuffer buffer = maxValue.toByteBuffer();
      if (validator.checkValidity(buffer) == Validity.VALID) {
        array = incrementUtf8(truncateUtf8(buffer, length));
      } else {
        array = increment(truncate(buffer, length));
      }
      return array == null ? maxValue : Binary.fromConstantByteArray(array);
    }

    // Simply truncate to length
    private byte[] truncate(ByteBuffer buffer, int length) {
      assert length < buffer.remaining();
      byte[] array = new byte[length];
      buffer.get(array);
      return array;
    }

    // Trying to increment the bytes from the last one to the beginning
    private byte[] increment(byte[] array) {
      for (int i = array.length - 1; i >= 0; --i) {
        byte elem = array[i];
        ++elem;
        array[i] = elem;
        if (elem != 0) { // Did not overflow: 0xFF -> 0x00
          return array;
        }
      }
      return null;
    }

    // Truncates the buffer to length or less so the remaining bytes form a valid UTF-8 string
    private byte[] truncateUtf8(ByteBuffer buffer, int length) {
      assert length < buffer.remaining();
      ByteBuffer newBuffer = buffer.slice();
      newBuffer.limit(newBuffer.position() + length);
      while (validator.checkValidity(newBuffer) != Validity.VALID) {
        newBuffer.limit(newBuffer.limit() - 1);
        if (newBuffer.remaining() == 0) {
          return null;
        }
      }
      byte[] array = new byte[newBuffer.remaining()];
      newBuffer.get(array);
      return array;
    }

    // Trying to increment the bytes from the last one to the beginning until the bytes form a valid UTF-8 string
    private byte[] incrementUtf8(byte[] array) {
      if (array == null) {
        return null;
      }
      ByteBuffer buffer = ByteBuffer.wrap(array);
      for (int i = array.length - 1; i >= 0; --i) {
        byte prev = array[i];
        byte inc = prev;
        while (++inc != 0) { // Until overflow: 0xFF -> 0x00
          array[i] = inc;
          switch (validator.checkValidity(buffer)) {
            case VALID:
              return array;
            case UNMAPPABLE:
              continue; // Increment the i byte once more
            case MALFORMED:
              break; // Stop incrementing the i byte; go to the i-1
          }
          break; // MALFORMED
        }
        array[i] = prev;
      }
      return null; // All characters are the largest possible; unable to increment
    }
  };

  static BinaryTruncator getTruncator(PrimitiveType type) {
    if (type == null) {
      return NO_OP_TRUNCATOR;
    }
    switch (type.getPrimitiveTypeName()) {
      case INT96:
        return NO_OP_TRUNCATOR;
      case BINARY:
      case FIXED_LEN_BYTE_ARRAY:
        OriginalType originalType = type.getOriginalType();
        if (originalType == null) {
          return DEFAULT_UTF8_TRUNCATOR;
        }
        switch (originalType) {
          case UTF8:
          case ENUM:
          case JSON:
          case BSON:
            return DEFAULT_UTF8_TRUNCATOR;
          default:
            return NO_OP_TRUNCATOR;
        }
      default:
        throw new IllegalArgumentException("No truncator is available for the type: " + type);
    }
  }

  abstract Binary truncateMin(Binary minValue, int length);

  abstract Binary truncateMax(Binary maxValue, int length);
}
