/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.query;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.ConstantScoreQuery;
import org.apache.lucene.search.Filter;
import org.apache.lucene.search.FilteredQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.join.BitDocIdSetFilter;
import org.apache.lucene.search.join.ScoreMode;
import org.apache.lucene.search.join.ToParentBlockJoinQuery;
import org.apache.lucene.util.BitDocIdSet;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.mapper.object.ObjectMapper;
import org.elasticsearch.index.search.nested.NonNestedDocsFilter;

import java.io.IOException;

public class NestedQueryParser implements QueryParser {

    public static final String NAME = "nested";

    @Inject
    public NestedQueryParser() {
    }

    @Override
    public String[] names() {
        return new String[]{NAME, Strings.toCamelCase(NAME)};
    }

    @Override
    public Query parse(QueryParseContext parseContext) throws IOException, QueryParsingException {
        XContentParser parser = parseContext.parser();

        Query query = null;
        boolean queryFound = false;
        Filter filter = null;
        boolean filterFound = false;
        float boost = 1.0f;
        String path = null;
        ScoreMode scoreMode = ScoreMode.Avg;
        String queryName = null;

        // we need a late binding filter so we can inject a parent nested filter inner nested queries
        LateBindingParentFilter currentParentFilterContext = parentFilterContext.get();

        LateBindingParentFilter usAsParentFilter = new LateBindingParentFilter();
        parentFilterContext.set(usAsParentFilter);

        try {
            String currentFieldName = null;
            XContentParser.Token token;
            while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                if (token == XContentParser.Token.FIELD_NAME) {
                    currentFieldName = parser.currentName();
                } else if (token == XContentParser.Token.START_OBJECT) {
                    if ("query".equals(currentFieldName)) {
                        queryFound = true;
                        query = parseContext.parseInnerQuery();
                    } else if ("filter".equals(currentFieldName)) {
                        filterFound = true;
                        filter = parseContext.parseInnerFilter();
                    } else {
                        throw new QueryParsingException(parseContext.index(), "[nested] query does not support [" + currentFieldName + "]");
                    }
                } else if (token.isValue()) {
                    if ("path".equals(currentFieldName)) {
                        path = parser.text();
                    } else if ("boost".equals(currentFieldName)) {
                        boost = parser.floatValue();
                    } else if ("score_mode".equals(currentFieldName) || "scoreMode".equals(currentFieldName)) {
                        String sScoreMode = parser.text();
                        if ("avg".equals(sScoreMode)) {
                            scoreMode = ScoreMode.Avg;
                        } else if ("max".equals(sScoreMode)) {
                            scoreMode = ScoreMode.Max;
                        } else if ("total".equals(sScoreMode) || "sum".equals(sScoreMode)) {
                            scoreMode = ScoreMode.Total;
                        } else if ("none".equals(sScoreMode)) {
                            scoreMode = ScoreMode.None;
                        } else {
                            throw new QueryParsingException(parseContext.index(), "illegal score_mode for nested query [" + sScoreMode + "]");
                        }
                    } else if ("_name".equals(currentFieldName)) {
                        queryName = parser.text();
                    } else {
                        throw new QueryParsingException(parseContext.index(), "[nested] query does not support [" + currentFieldName + "]");
                    }
                }
            }
            if (!queryFound && !filterFound) {
                throw new QueryParsingException(parseContext.index(), "[nested] requires either 'query' or 'filter' field");
            }
            if (path == null) {
                throw new QueryParsingException(parseContext.index(), "[nested] requires 'path' field");
            }

            if (query == null && filter == null) {
                return null;
            }

            if (filter != null) {
                query = new ConstantScoreQuery(filter);
            }

            MapperService.SmartNameObjectMapper mapper = parseContext.smartObjectMapper(path);
            if (mapper == null) {
                throw new QueryParsingException(parseContext.index(), "[nested] failed to find nested object under path [" + path + "]");
            }
            ObjectMapper objectMapper = mapper.mapper();
            if (objectMapper == null) {
                throw new QueryParsingException(parseContext.index(), "[nested] failed to find nested object under path [" + path + "]");
            }
            if (!objectMapper.nested().isNested()) {
                throw new QueryParsingException(parseContext.index(), "[nested] nested object under path [" + path + "] is not of nested type");
            }

            BitDocIdSetFilter childFilter = parseContext.bitsetFilter(objectMapper.nestedTypeFilter());
            usAsParentFilter.filter = childFilter;
            // wrap the child query to only work on the nested path type
            query = new FilteredQuery(query, childFilter);

            BitDocIdSetFilter parentFilter = currentParentFilterContext;
            if (parentFilter == null) {
                parentFilter = parseContext.bitsetFilter(NonNestedDocsFilter.INSTANCE);
                // don't do special parent filtering, since we might have same nested mapping on two different types
                //if (mapper.hasDocMapper()) {
                //    // filter based on the type...
                //    parentFilter = mapper.docMapper().typeFilter();
                //}
            } else {
                parentFilter = parseContext.bitsetFilter(parentFilter);
            }
            ToParentBlockJoinQuery joinQuery = new ToParentBlockJoinQuery(query, parentFilter, scoreMode);
            joinQuery.setBoost(boost);
            if (queryName != null) {
                parseContext.addNamedQuery(queryName, joinQuery);
            }
            return joinQuery;
        } finally {
            // restore the thread local one...
            parentFilterContext.set(currentParentFilterContext);
        }
    }

    // TODO: Change this mechanism in favour of how parent nested object type is resolved in nested and reverse_nested agg
    // with this also proper validation can be performed on what is a valid nested child nested object type to be used
    public static ThreadLocal<LateBindingParentFilter> parentFilterContext = new ThreadLocal<>();

    public static class LateBindingParentFilter extends BitDocIdSetFilter {

        public BitDocIdSetFilter filter;

        @Override
        public int hashCode() {
            return filter.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof LateBindingParentFilter)) return false;
            return filter.equals(((LateBindingParentFilter) obj).filter);
        }

        @Override
        public String toString() {
            return filter.toString();
        }

        @Override
        public BitDocIdSet getDocIdSet(LeafReaderContext ctx) throws IOException {
            return filter.getDocIdSet(ctx);
        }
    }
}
