/*
 * Copyright (C) 2022-2023 Velocity Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.velocitypowered.proxy.protocol.packet.chat;

import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.protocol.MinecraftPacket;
import javax.annotation.Nullable;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * A precisely ordered queue which allows for outside entries into the ordered queue through
 * piggybacking timestamps.
 */
public class ChatQueue {

  private final Object internalLock;
  private final ConnectedPlayer player;
  private CompletableFuture<ChatState> head;

  /**
   * Instantiates a {@link ChatQueue} for a specific {@link ConnectedPlayer}.
   *
   * @param player the {@link ConnectedPlayer} to maintain the queue for.
   */
  public ChatQueue(ConnectedPlayer player) {
    this.player = player;
    this.head = CompletableFuture.completedFuture(ChatState.INITIAL);
    this.internalLock = new Object();
  }

  private void queueTask(Task task) {
    synchronized (internalLock) {
      MinecraftConnection smc = player.ensureAndGetCurrentServer().ensureConnected();
      head = head.thenCompose(chatState -> {
        try {
          return task.update(chatState, smc).exceptionally(ignored -> chatState);
        } catch (Throwable ignored) {
          return CompletableFuture.completedFuture(chatState);
        }
      });
    }
  }

  /**
   * Queues a packet sent from the player - all packets must wait until this processes to send their
   * packets. This maintains order on the server-level for the client insertions of commands
   * and messages. All entries are locked through an internal object lock.
   *
   * @param nextPacket the {@link CompletableFuture} which will provide the next-processed packet.
   * @param timestamp  the new {@link Instant} timestamp of this packet to update the internal chat state.
   */
  public void queuePacket(CompletableFuture<MinecraftPacket> nextPacket, @Nullable Instant timestamp) {
    queueTask((chatState, smc) -> nextPacket.handleAsync((packet, throwable) -> {
      if (packet != null && !smc.isClosed()) {
        smc.write(packet);
      }
      return chatState.update(timestamp);
    }, smc.eventLoop()));
  }

  /**
   * Hijacks the latest sent packet's chat state to provide an in-order packet without polling the
   * physical, or prior packets sent through the stream.
   *
   * @param packetFunction a function that maps the prior {@link ChatState} into a new packet.
   * @param <T>            the type of packet to send.
   */
  public <T extends MinecraftPacket> void queuePacket(Function<ChatState, T> packetFunction) {
    queueTask((chatState, smc) -> {
      T packet = packetFunction.apply(chatState);
      return CompletableFuture.supplyAsync(() -> {
        if (!smc.isClosed()) {
          smc.write(packet);
        }
        return chatState;
      }, smc.eventLoop());
    });
  }

  private interface Task {
    CompletableFuture<ChatState> update(ChatState chatState, MinecraftConnection smc);
  }

  public static class ChatState {
    private static final ChatState INITIAL = new ChatState(Instant.EPOCH);

    public final Instant lastTimestamp;

    private ChatState(Instant lastTimestamp) {
      this.lastTimestamp = lastTimestamp;
    }

    public ChatState update(@Nullable Instant timestamp) {
      if (timestamp == null) {
        return this;
      }
      return new ChatState(timestamp);
    }
  }
}
