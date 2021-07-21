/*
 * Copyright 2021 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.ksql.util;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.confluent.ksql.name.SourceName;
import io.confluent.ksql.query.KafkaStreamsBuilder;
import io.confluent.ksql.query.QueryError;
import io.confluent.ksql.query.QueryErrorClassifier;
import io.confluent.ksql.query.QueryId;
import io.confluent.ksql.rest.entity.StreamsTaskMetadata;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.LagInfo;
import org.apache.kafka.streams.StreamsMetadata;
import org.apache.kafka.streams.errors.StreamsException;
import org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler;
import org.apache.kafka.streams.processor.internals.namedtopology.KafkaStreamsNamedTopologyWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SharedKafkaStreamsRuntimeImpl implements SharedKafkaStreamsRuntime {

  private static final Logger LOG = LoggerFactory.getLogger(SharedKafkaStreamsRuntimeImpl.class);

  private final KafkaStreamsBuilder kafkaStreamsBuilder;
  private QueryErrorClassifier errorClassifier ;
  private KafkaStreamsNamedTopologyWrapper kafkaStreams;
  private ImmutableMap<String, Object> streamsProperties;
  private final QueryMetadataImpl.TimeBoundedQueue queryErrors;
  private final Map<String, PersistentQueriesInSharedRuntimesImpl> metadata;
  public final Map<QueryId, Set<SourceName>> sources;

  public SharedKafkaStreamsRuntimeImpl(final KafkaStreamsBuilder kafkaStreamsBuilder,
                                       final int maxQueryErrorsQueueSize,
                                       final Map<String, Object> streamsProperties) {
    this.kafkaStreamsBuilder = kafkaStreamsBuilder;
    kafkaStreams = kafkaStreamsBuilder.build(streamsProperties);
    queryErrors
        = new QueryMetadataImpl.TimeBoundedQueue(Duration.ofHours(1), maxQueryErrorsQueueSize);
    this.streamsProperties = ImmutableMap.copyOf(streamsProperties);
    metadata = new ConcurrentHashMap<>();
    sources = new ConcurrentHashMap<>();
    kafkaStreams.setUncaughtExceptionHandler(this::uncaughtHandler);
    kafkaStreams.start();
  }

  public void markSources(final QueryId queryId, final Set<SourceName> sourceNames) {
    sources.put(queryId, sourceNames);
  }

  public void addQuery(
          final QueryErrorClassifier errorClassifier,
          final Map<String, Object> streamsProperties,
          final PersistentQueriesInSharedRuntimesImpl persistentQueriesInSharedRuntimesImpl,
          final QueryId queryId) {
    this.errorClassifier = errorClassifier;
    if(metadata.containsKey(queryId.toString())) {
      kafkaStreams.removeNamedTopology(queryId.toString());
    }
    metadata.put(queryId.toString(), persistentQueriesInSharedRuntimesImpl);
    LOG.debug("mapping {}", metadata);
  }

  public StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse uncaughtHandler(
      final Throwable e
  ) {
    QueryError.Type errorType = QueryError.Type.UNKNOWN;
    try {
      errorType = errorClassifier.classify(e);
    } catch (final Exception classificationException) {
      LOG.error("Error classifying unhandled exception", classificationException);
    } finally {
      // If error classification throws then we consider the error to be an UNKNOWN error.
      // We notify listeners and add the error to the errors queue in the finally block to ensure
      // all listeners and consumers of the error queue (e.g. the API) can see the error. Similarly,
      // log in finally block to make sure that if there's ever an error in the classification
      // we still get this in our logs.
      final QueryError queryError =
          new QueryError(
              System.currentTimeMillis(),
              Throwables.getStackTraceAsString(e),
              errorType
          );
      for (PersistentQueriesInSharedRuntimesImpl query: metadata.values()) {
        query.getListener().onError(metadata.get(query.getQueryId().toString()), queryError);
      }
      queryErrors.add(queryError);
      LOG.error(
          "Unhandled exception caught in streams thread {}. ({})",
          Thread.currentThread().getName(),
          errorType,
          e
      );
    }
    return StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.REPLACE_THREAD;
  }

  public KafkaStreams getKafkaStreams() {
    return kafkaStreams;
  }

  public KafkaStreams.State state() {
    return kafkaStreams.state();
  }

  public Collection<StreamsMetadata> allMetadata() {
    return kafkaStreams.metadataForAllStreamsClients();
  }

  public Set<StreamsTaskMetadata> getTaskMetadata() {
    return kafkaStreams.metadataForLocalThreads()
                       .stream()
                       .flatMap(t -> t.activeTasks().stream())
                       .map(StreamsTaskMetadata::fromStreamsTaskMetadata)
                       .collect(Collectors.toSet());
  }

  public void restart(final QueryId queryId) {
    final KafkaStreamsNamedTopologyWrapper newKafkaStreams = kafkaStreamsBuilder
            .build(streamsProperties);
    newKafkaStreams.setUncaughtExceptionHandler(this::uncaughtHandler);
    kafkaStreams.close();
    newKafkaStreams.start();
    for (PersistentQueriesInSharedRuntimesImpl query: metadata.values()) {
      newKafkaStreams.addNamedTopology(query.getTopology());
    }
    kafkaStreams = newKafkaStreams;
  }

  public boolean isError(final QueryId queryId) {
    return !queryErrors.toImmutableList().isEmpty();
  }

  public void close(final QueryId queryId) {
    metadata.remove(queryId.toString());
    if (kafkaStreams.state() == KafkaStreams.State.RUNNING
            || kafkaStreams.state() == KafkaStreams.State.REBALANCING) {
          try {
              kafkaStreams.removeNamedTopology(queryId.toString());
          } catch (IllegalArgumentException e) {
              //don't block
          }
    }
  }

  public synchronized void close() {
    for (String query: metadata.keySet()) {
      metadata.remove(query);
    }
    kafkaStreams.close(Duration.ZERO);
  }

  public void start(final QueryId queryId) {
    if (metadata.containsKey(queryId.toString()) && !metadata.get(queryId.toString()).everStarted) {
      kafkaStreams.addNamedTopology(metadata.get(queryId.toString()).getTopology());
    } else {
      throw new IllegalArgumentException("query not added to runtime");
    }
  }

  public List<QueryError> getQueryErrors() {
    return queryErrors.toImmutableList();
  }

  public Map<String, Map<Integer, LagInfo>> allLocalStorePartitionLags(final QueryId queryId) {
    try {
      return kafkaStreams.allLocalStorePartitionLags();
    } catch (IllegalStateException | StreamsException e) {
      LOG.error(e.getMessage());
      return ImmutableMap.of();
    }
  }

  public Map<String, Object> getStreamProperties() {
    return streamsProperties;
  }

  public Set<SourceName> getSources() {
    return ImmutableSet.copyOf(
        sources
            .values()
            .stream()
            .flatMap(Collection::stream)
            .collect(Collectors.toSet()));
  }

  public Set<QueryId> getQueries() {
    return ImmutableSet.copyOf(sources.keySet());
  }
}