/**
 * Copyright 2016-2019 The Reaktivity Project
 *
 * The Reaktivity Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.reaktivity.command.log.internal.spy;

import static org.agrona.BitUtil.align;
import static org.agrona.concurrent.ringbuffer.OneToOneRingBuffer.PADDING_MSG_TYPE_ID;
import static org.agrona.concurrent.ringbuffer.RecordDescriptor.ALIGNMENT;
import static org.agrona.concurrent.ringbuffer.RecordDescriptor.HEADER_LENGTH;
import static org.agrona.concurrent.ringbuffer.RecordDescriptor.lengthOffset;
import static org.agrona.concurrent.ringbuffer.RecordDescriptor.typeOffset;
import static org.agrona.concurrent.ringbuffer.RingBufferDescriptor.HEAD_POSITION_OFFSET;
import static org.agrona.concurrent.ringbuffer.RingBufferDescriptor.TAIL_POSITION_OFFSET;
import static org.agrona.concurrent.ringbuffer.RingBufferDescriptor.TRAILER_LENGTH;
import static org.agrona.concurrent.ringbuffer.RingBufferDescriptor.checkCapacity;

import java.util.concurrent.atomic.AtomicLong;

import org.agrona.DirectBuffer;
import org.agrona.concurrent.AtomicBuffer;
import org.reaktivity.nukleus.function.MessagePredicate;

public class OneToOneRingBufferSpy implements RingBufferSpy
{
    private final int capacity;
    private final AtomicLong headPosition;
    private final AtomicBuffer buffer;


    public OneToOneRingBufferSpy(
        final AtomicBuffer buffer)
    {
        this.buffer = buffer;
        checkCapacity(buffer.capacity());
        capacity = buffer.capacity() - TRAILER_LENGTH;

        buffer.verifyAlignment();

        headPosition = new AtomicLong();
    }

    public void resetHead()
    {
        headPosition.lazySet(buffer.getLong(capacity + HEAD_POSITION_OFFSET));
    }

    @Override
    public DirectBuffer buffer()
    {
        return buffer;
    }

    @Override
    public long producerPosition()
    {
        return buffer.getLong(buffer.capacity() - TRAILER_LENGTH + TAIL_POSITION_OFFSET);
    }

    @Override
    public long consumerPosition()
    {
        return buffer.getLong(buffer.capacity() - TRAILER_LENGTH + HEAD_POSITION_OFFSET);
    }

    @Override
    public int spy(
        final MessagePredicate handler)
    {
        return spy(handler, Integer.MAX_VALUE);
    }

    @Override
    public int spy(
        final MessagePredicate handler,
        final int messageCountLimit)
    {
        int messagesRead = 0;

        final AtomicBuffer buffer = this.buffer;
        final long head = headPosition.get();

        int bytesRead = 0;

        final int capacity = this.capacity;
        final int headIndex = (int)head & (capacity - 1);
        final int contiguousBlockLength = capacity - headIndex;

        try
        {
            while ((bytesRead < contiguousBlockLength) && (messagesRead < messageCountLimit))
            {
                final int recordIndex = headIndex + bytesRead;
                final int recordLength = buffer.getIntVolatile(lengthOffset(recordIndex));
                if (recordLength <= 0)
                {
                    break;
                }

                bytesRead += align(recordLength, ALIGNMENT);

                final int messageTypeId = buffer.getInt(typeOffset(recordIndex));
                if (PADDING_MSG_TYPE_ID == messageTypeId)
                {
                    continue;
                }

                ++messagesRead;
                if (!handler.test(messageTypeId, buffer, recordIndex + HEADER_LENGTH, recordLength - HEADER_LENGTH))
                {
                    bytesRead -= align(recordLength, ALIGNMENT);
                    break;
                }
            }
        }
        finally
        {
            if (bytesRead != 0)
            {
                headPosition.lazySet(head + bytesRead);
            }
        }

        return messagesRead;
    }
}
