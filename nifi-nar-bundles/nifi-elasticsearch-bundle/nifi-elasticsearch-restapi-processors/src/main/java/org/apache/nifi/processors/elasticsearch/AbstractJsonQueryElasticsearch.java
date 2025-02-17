/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.processors.elasticsearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.annotation.lifecycle.OnStopped;
import org.apache.nifi.components.AllowableValue;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.elasticsearch.ElasticSearchClientService;
import org.apache.nifi.elasticsearch.ElasticsearchException;
import org.apache.nifi.elasticsearch.SearchResponse;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.flowfile.attributes.CoreAttributes;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.processors.elasticsearch.api.JsonQueryParameters;
import org.apache.nifi.util.StopWatch;
import org.apache.nifi.util.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public abstract class AbstractJsonQueryElasticsearch<Q extends JsonQueryParameters> extends AbstractProcessor implements ElasticsearchRestProcessor {
    public static final Relationship REL_ORIGINAL = new Relationship.Builder().name("original")
            .description("All original flowfiles that don't cause an error to occur go to this relationship.").build();
    public static final Relationship REL_HITS = new Relationship.Builder().name("hits")
            .description("Search hits are routed to this relationship.")
            .build();
    public static final Relationship REL_AGGREGATIONS = new Relationship.Builder().name("aggregations")
            .description("Aggregations are routed to this relationship.")
            .build();

    public static final AllowableValue FLOWFILE_PER_HIT = new AllowableValue(
            "splitUp-yes",
            "Per Hit",
            "Flowfile per hit."
    );
    public static final AllowableValue FLOWFILE_PER_RESPONSE = new AllowableValue(
            "splitUp-no",
            "Per Response",
            "Flowfile per response."
    );

    public static final PropertyDescriptor SEARCH_RESULTS_SPLIT = new PropertyDescriptor.Builder()
            .name("el-rest-split-up-hits")
            .displayName("Search Results Split")
            .description("Output a flowfile containing all hits or one flowfile for each individual hit.")
            .allowableValues(FLOWFILE_PER_RESPONSE, FLOWFILE_PER_HIT)
            .defaultValue(FLOWFILE_PER_RESPONSE.getValue())
            .required(true)
            .expressionLanguageSupported(ExpressionLanguageScope.NONE)
            .build();
    public static final PropertyDescriptor AGGREGATION_RESULTS_SPLIT = new PropertyDescriptor.Builder()
            .name("el-rest-split-up-aggregations")
            .displayName("Aggregation Results Split")
            .description("Output a flowfile containing all aggregations or one flowfile for each individual aggregation.")
            .allowableValues(FLOWFILE_PER_RESPONSE, FLOWFILE_PER_HIT)
            .defaultValue(FLOWFILE_PER_RESPONSE.getValue())
            .required(true)
            .expressionLanguageSupported(ExpressionLanguageScope.NONE)
            .build();

    private static final Set<Relationship> relationships;
    private static final List<PropertyDescriptor> propertyDescriptors;

    AtomicReference<ElasticSearchClientService> clientService;
    String splitUpHits;
    private String splitUpAggregations;

    final ObjectMapper mapper = new ObjectMapper();

    static {
        final Set<Relationship> rels = new HashSet<>();
        rels.add(REL_ORIGINAL);
        rels.add(REL_FAILURE);
        rels.add(REL_HITS);
        rels.add(REL_AGGREGATIONS);
        relationships = Collections.unmodifiableSet(rels);

        final List<PropertyDescriptor> descriptors = new ArrayList<>();
        descriptors.add(QUERY);
        descriptors.add(QUERY_ATTRIBUTE);
        descriptors.add(INDEX);
        descriptors.add(TYPE);
        descriptors.add(CLIENT_SERVICE);
        descriptors.add(SEARCH_RESULTS_SPLIT);
        descriptors.add(AGGREGATION_RESULTS_SPLIT);

        propertyDescriptors = Collections.unmodifiableList(descriptors);
    }

    @Override
    public Set<Relationship> getRelationships() {
        return relationships;
    }

    @Override
    public List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return propertyDescriptors;
    }

    @Override
    protected PropertyDescriptor getSupportedDynamicPropertyDescriptor(final String propertyDescriptorName) {
        return new PropertyDescriptor.Builder()
                .name(propertyDescriptorName)
                .required(false)
                .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
                .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
                .dynamic(true)
                .build();
    }

    @OnScheduled
    public void onScheduled(final ProcessContext context) {
        clientService = new AtomicReference<>(context.getProperty(CLIENT_SERVICE).asControllerService(ElasticSearchClientService.class));

        splitUpHits = context.getProperty(SEARCH_RESULTS_SPLIT).getValue();
        splitUpAggregations = context.getProperty(AGGREGATION_RESULTS_SPLIT).getValue();
    }

    @OnStopped
    public void onStopped() {
        this.clientService = null;
    }

    @Override
    public void onTrigger(final ProcessContext context, final ProcessSession session) {
        FlowFile input = null;
        if (context.hasIncomingConnection()) {
            input = session.get();

            if (input == null && context.hasNonLoopConnection()) {
                return;
            }
        }

        try {
            final Q queryJsonParameters = buildJsonQueryParameters(input, context, session);

            List<FlowFile> hitsFlowFiles = new ArrayList<>();
            final StopWatch stopWatch = new StopWatch(true);
            final SearchResponse response = doQuery(queryJsonParameters, hitsFlowFiles, session, context, input, stopWatch);

            finishQuery(input, queryJsonParameters, session, context, response);
        } catch (final ElasticsearchException ese) {
            final String msg = String.format("Encountered a server-side problem with Elasticsearch. %s",
                    ese.isElastic() ? "Routing to retry." : "Routing to failure");
            getLogger().error(msg, ese);
            if (input != null) {
                session.penalize(input);
                input = session.putAttribute(input, "elasticsearch.query.error", ese.getMessage());
                session.transfer(input, ese.isElastic() ? REL_RETRY : REL_FAILURE);
            }
        } catch (Exception ex) {
            getLogger().error("Could not query documents.", ex);
            if (input != null) {
                input = session.putAttribute(input, "elasticsearch.query.error", ex.getMessage());
                session.transfer(input, REL_FAILURE);
            }
            context.yield();
        }
    }

    abstract Q buildJsonQueryParameters(final FlowFile input, final ProcessContext context, final ProcessSession session) throws IOException;

    void populateCommonJsonQueryParameters(final Q queryJsonParameters, final FlowFile input, final ProcessContext context,
                                           final ProcessSession session) throws IOException {
        final String query = getQuery(input, context, session);
        final String index = context.getProperty(INDEX).evaluateAttributeExpressions(input).getValue();
        final String type = context.getProperty(TYPE).evaluateAttributeExpressions(input).getValue();
        final String queryAttr = context.getProperty(QUERY_ATTRIBUTE).isSet()
                ? context.getProperty(QUERY_ATTRIBUTE).evaluateAttributeExpressions(input).getValue()
                : null;

        queryJsonParameters.setQuery(query);
        queryJsonParameters.setIndex(index);
        queryJsonParameters.setType(type);
        queryJsonParameters.setQueryAttr(queryAttr);
    }

    abstract SearchResponse doQuery(final Q queryJsonParameters, final List<FlowFile> hitsFlowFiles, final ProcessSession session,
                                    final ProcessContext context, final FlowFile input, final StopWatch stopWatch) throws IOException;

    abstract void finishQuery(final FlowFile input, final Q queryParameters, final ProcessSession session, final ProcessContext context,
                              final SearchResponse response) throws IOException;

    FlowFile createChildFlowFile(final ProcessSession session, final FlowFile parent) {
        return parent != null ? session.create(parent) : session.create();
    }

    private FlowFile writeAggregationFlowFileContents(final String name, final Integer number, final String json,
                                                      final ProcessSession session, final FlowFile aggFlowFile,
                                                      final Map<String, String> attributes) {
        FlowFile ff = session.write(aggFlowFile, out -> out.write(json.getBytes()));
        ff = session.putAllAttributes(ff, new HashMap<String, String>(){{
            if (name != null) {
                put("aggregation.name", name);
            }
            if (number != null) {
                put("aggregation.number", number.toString());
            }
        }});

        return session.putAllAttributes(ff, attributes);
    }

    private void handleAggregations(final Map<String, Object> aggregations, final ProcessSession session,
                                    final FlowFile parent, final Map<String, String> attributes,
                                    final String transitUri, final StopWatch stopWatch) throws IOException {
        if (aggregations != null && !aggregations.isEmpty()) {
            final List<FlowFile> aggsFlowFiles = new ArrayList<>();
            if (splitUpAggregations.equals(FLOWFILE_PER_HIT.getValue())) {
                int aggCount = 0;
                for (final Map.Entry<String, Object> agg : aggregations.entrySet()) {
                    final FlowFile aggFlowFile = createChildFlowFile(session, parent);
                    final String aggJson = mapper.writeValueAsString(agg.getValue());
                    aggsFlowFiles.add(writeAggregationFlowFileContents(agg.getKey(), ++aggCount, aggJson, session, aggFlowFile, attributes));
                }
            } else {
                final FlowFile aggFlowFile = createChildFlowFile(session, parent);
                final String json = mapper.writeValueAsString(aggregations);
                aggsFlowFiles.add(writeAggregationFlowFileContents(null, null, json, session, aggFlowFile, attributes));
            }

            if (!aggsFlowFiles.isEmpty()) {
                session.transfer(aggsFlowFiles, REL_AGGREGATIONS);
                aggsFlowFiles.forEach(ff -> session.getProvenanceReporter().receive(ff, transitUri, stopWatch.getElapsed(TimeUnit.MILLISECONDS)));
            }
        }
    }

    private FlowFile writeHitFlowFile(final int count, final String json, final ProcessSession session,
                                      final FlowFile hitFlowFile, final Map<String, String> attributes) {
        final FlowFile ff = session.write(hitFlowFile, out -> out.write(json.getBytes()));
        attributes.put("hit.count", Integer.toString(count));

        return session.putAllAttributes(ff, attributes);
    }

    /*
     * The List<FlowFile> hitsFlowFiles parameter and return value are used in order to allow pagination of query results
     * in AbstractPaginatedJsonQueryElasticsearch. The List is created in onTrigger and passed to doQuery => handleResponse => handleHits,
     * for non-paginated queries the return value will always be an empty List as the FlowFiles will have been transferred;
     * for paginated queries, the List could contain one (or more) FlowFiles, to which further hits may be appended when the next
     * SearchResponse is processed, i.e. this approach allows recursion for paginated queries, but is unnecessary for single-response queries.
     */
    List<FlowFile> handleHits(final List<Map<String, Object>> hits, final Q queryJsonParameters, final ProcessSession session,
                              final FlowFile parent, final Map<String, String> attributes, final List<FlowFile> hitsFlowFiles,
                              final String transitUri, final StopWatch stopWatch) throws IOException {
        if (hits != null && !hits.isEmpty()) {
            if (FLOWFILE_PER_HIT.getValue().equals(splitUpHits)) {
                for (final Map<String, Object> hit : hits) {
                    final FlowFile hitFlowFile = createChildFlowFile(session, parent);
                    final String json = mapper.writeValueAsString(hit);
                    hitsFlowFiles.add(writeHitFlowFile(1, json, session, hitFlowFile, attributes));
                }
            } else {
                final FlowFile hitFlowFile = createChildFlowFile(session, parent);
                final String json = mapper.writeValueAsString(hits);
                hitsFlowFiles.add(writeHitFlowFile(hits.size(), json, session, hitFlowFile, attributes));
            }
        }

        transferResultFlowFiles(session, hitsFlowFiles, transitUri, stopWatch);

        return hitsFlowFiles;
    }

    private void transferResultFlowFiles(final ProcessSession session, final List<FlowFile> hitsFlowFiles, final String transitUri,
                                         final StopWatch stopWatch) {
        // output any results
        if (!hitsFlowFiles.isEmpty()) {
            session.transfer(hitsFlowFiles, REL_HITS);
            hitsFlowFiles.forEach(ff -> session.getProvenanceReporter().receive(ff, transitUri, stopWatch.getElapsed(TimeUnit.MILLISECONDS)));
            hitsFlowFiles.clear();
        }
    }

    List<FlowFile> handleResponse(final SearchResponse response, final boolean newQuery, final Q queryJsonParameters,
                                  final List<FlowFile> hitsFlowFiles, final ProcessSession session, final FlowFile input,
                                  final StopWatch stopWatch) throws IOException {
        final Map<String, String> attributes = new HashMap<>();
        attributes.put(CoreAttributes.MIME_TYPE.key(), "application/json");
        if (StringUtils.isNotBlank(queryJsonParameters.getQueryAttr())) {
            attributes.put(queryJsonParameters.getQueryAttr(), queryJsonParameters.getQuery());
        }

        final String transitUri = clientService.get().getTransitUrl(queryJsonParameters.getIndex(), queryJsonParameters.getType());
        if (newQuery) {
            // only output aggregations from initial query
            // (omitted from subsequent pages as aggs are calculated across the entire result set, not per page)
            handleAggregations(response.getAggregations(), session, input, attributes, transitUri, stopWatch);
        }

        final List<FlowFile> resultFlowFiles = handleHits(response.getHits(), queryJsonParameters, session, input,
                attributes, hitsFlowFiles, transitUri, stopWatch);
        queryJsonParameters.addHitCount(response.getHits().size());

        return resultFlowFiles;
    }
}
