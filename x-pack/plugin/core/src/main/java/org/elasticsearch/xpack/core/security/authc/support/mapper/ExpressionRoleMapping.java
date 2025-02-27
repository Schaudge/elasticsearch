/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.core.security.authc.support.mapper;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.ParsingException;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.xcontent.LoggingDeprecationHandler;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.xcontent.ObjectParser;
import org.elasticsearch.xcontent.ObjectParser.ValueType;
import org.elasticsearch.xcontent.ParseField;
import org.elasticsearch.xcontent.ToXContentObject;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.xpack.core.security.authc.support.UserRoleMapper;
import org.elasticsearch.xpack.core.security.authc.support.mapper.expressiondsl.ExpressionModel;
import org.elasticsearch.xpack.core.security.authc.support.mapper.expressiondsl.ExpressionParser;
import org.elasticsearch.xpack.core.security.authc.support.mapper.expressiondsl.RoleMapperExpression;
import org.elasticsearch.xpack.core.security.authz.RoleDescriptor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.elasticsearch.common.Strings.format;

/**
 * A representation of a single role-mapping for use in NativeRoleMappingStore.
 * Logically, this represents a set of roles that should be applied to any user where a boolean
 * expression evaluates to <code>true</code>.
 *
 * @see RoleMapperExpression
 * @see ExpressionParser
 */
public class ExpressionRoleMapping implements ToXContentObject, Writeable {

    /**
     * Reserved suffix for read-only operator-defined role mappings.
     * This suffix is added to the name of all cluster-state role mappings returned via
     * the {@code TransportGetRoleMappingsAction} action.
     */
    public static final String READ_ONLY_ROLE_MAPPING_SUFFIX = "-read-only-operator-mapping";
    /**
     * Reserved metadata field to mark role mappings as read-only.
     * This field is added to the metadata of all cluster-state role mappings returned via
     * the {@code TransportGetRoleMappingsAction} action.
     */
    public static final String READ_ONLY_ROLE_MAPPING_METADATA_FLAG = "_read_only";
    private static final ObjectParser<Builder, String> PARSER = new ObjectParser<>("role-mapping", Builder::new);

    /**
     * The Upgrade API added a 'type' field when converting from 5 to 6.
     * We don't use it, but we need to skip it if it exists.
     */
    private static final String UPGRADE_API_TYPE_FIELD = "type";

    static {
        PARSER.declareStringArray(Builder::roles, Fields.ROLES);
        PARSER.declareObjectArray(Builder::roleTemplates, (parser, ctx) -> TemplateRoleName.parse(parser), Fields.ROLE_TEMPLATES);
        PARSER.declareField(Builder::rules, ExpressionParser::parseObject, Fields.RULES, ValueType.OBJECT);
        PARSER.declareField(Builder::metadata, XContentParser::map, Fields.METADATA, ValueType.OBJECT);
        PARSER.declareBoolean(Builder::enabled, Fields.ENABLED);
        BiConsumer<Builder, String> ignored = (b, v) -> {};
        // skip the doc_type and type fields in case we're parsing directly from the index
        PARSER.declareString(ignored, new ParseField(NativeRoleMappingStoreField.DOC_TYPE_FIELD));
        PARSER.declareString(ignored, new ParseField(UPGRADE_API_TYPE_FIELD));
    }

    /**
     * Given the user information (in the form of {@link UserRoleMapper.UserData}) and a collection of {@link ExpressionRoleMapping}s,
     * this returns the set of role names that should be mapped to the user, according to the provided role mapping rules.
     */
    public static Set<String> resolveRoles(
        UserRoleMapper.UserData user,
        Collection<ExpressionRoleMapping> mappings,
        ScriptService scriptService,
        Logger logger
    ) {
        ExpressionModel model = user.asModel();
        Set<String> roles = mappings.stream()
            .filter(ExpressionRoleMapping::isEnabled)
            .filter(m -> m.getExpression().match(model))
            .flatMap(m -> {
                Set<String> roleNames = m.getRoleNames(scriptService, model);
                logger.trace(
                    () -> format("Applying role-mapping [%s] to user-model [%s] produced role-names [%s]", m.getName(), model, roleNames)
                );
                return roleNames.stream();
            })
            .collect(Collectors.toSet());
        logger.debug(() -> format("Mapping user [%s] to roles [%s]", user, roles));
        return roles;
    }

    private final String name;
    private final RoleMapperExpression expression;
    private final List<String> roles;
    private final List<TemplateRoleName> roleTemplates;
    private final Map<String, Object> metadata;
    private final boolean enabled;

    public ExpressionRoleMapping(
        String name,
        RoleMapperExpression expr,
        List<String> roles,
        List<TemplateRoleName> templates,
        Map<String, Object> metadata,
        boolean enabled
    ) {
        this.name = name;
        this.expression = expr;
        this.roles = roles == null ? Collections.emptyList() : roles;
        this.roleTemplates = templates == null ? Collections.emptyList() : templates;
        this.metadata = metadata;
        this.enabled = enabled;
    }

    public ExpressionRoleMapping(StreamInput in) throws IOException {
        this.name = in.readString();
        this.enabled = in.readBoolean();
        this.roles = in.readStringCollectionAsList();
        this.roleTemplates = in.readCollectionAsList(TemplateRoleName::new);
        this.expression = ExpressionParser.readExpression(in);
        this.metadata = in.readGenericMap();
    }

    public static boolean hasReadOnlySuffix(String name) {
        return name.endsWith(READ_ONLY_ROLE_MAPPING_SUFFIX);
    }

    public static void validateNoReadOnlySuffix(String name) {
        if (hasReadOnlySuffix(name)) {
            throw new IllegalArgumentException(
                "Invalid mapping name [" + name + "]. [" + READ_ONLY_ROLE_MAPPING_SUFFIX + "] is not an allowed suffix"
            );
        }
    }

    public static String addReadOnlySuffix(String name) {
        return name + READ_ONLY_ROLE_MAPPING_SUFFIX;
    }

    public static String removeReadOnlySuffixIfPresent(String name) {
        return name.endsWith(READ_ONLY_ROLE_MAPPING_SUFFIX)
            ? name.substring(0, name.length() - READ_ONLY_ROLE_MAPPING_SUFFIX.length())
            : name;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeString(name);
        out.writeBoolean(enabled);
        out.writeStringCollection(roles);
        out.writeCollection(roleTemplates);
        ExpressionParser.writeExpression(expression, out);
        out.writeGenericMap(metadata);
    }

    /**
     * The name of this mapping. The name exists for the sole purpose of providing a meaningful identifier for each mapping, so that it may
     * be referred to for update, retrieval or deletion. The name does not affect the set of roles that a mapping provides.
     */
    public String getName() {
        return name;
    }

    /**
     * The expression that determines whether the roles in this mapping should be applied to any given user.
     * If the expression
     * {@link RoleMapperExpression#match(ExpressionModel) matches} a
     * org.elasticsearch.xpack.security.authc.support.UserRoleMapper.UserData user, then the user should be assigned this mapping's
     * {@link #getRoles() roles}
     */
    public RoleMapperExpression getExpression() {
        return expression;
    }

    /**
     * The list of {@link RoleDescriptor roles} (specified by name) that should be assigned to users
     * that match the {@link #getExpression() expression} in this mapping.
     */
    public List<String> getRoles() {
        return roles != null ? Collections.unmodifiableList(roles) : Collections.emptyList();
    }

    /**
     * The list of {@link RoleDescriptor roles} (specified by a {@link TemplateRoleName template} that evaluates to one or more names)
     * that should be assigned to users that match the {@link #getExpression() expression} in this mapping.
     */
    public List<TemplateRoleName> getRoleTemplates() {
        return roleTemplates != null ? Collections.unmodifiableList(roleTemplates) : Collections.emptyList();
    }

    /**
     * Meta-data for this mapping. This exists for external systems of user to track information about this mapping such as where it was
     * sourced from, when it was loaded, etc.
     * This is not used within the mapping process, and does not affect whether the expression matches, nor which roles are assigned.
     */
    public Map<String, Object> getMetadata() {
        return metadata != null ? Collections.unmodifiableMap(metadata) : Collections.emptyMap();
    }

    /**
     * Whether this mapping is enabled. Mappings that are not enabled are not applied to users.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Whether this mapping is an operator defined/read only role mapping
     */
    public boolean isReadOnly() {
        return metadata != null && metadata.get(ExpressionRoleMapping.READ_ONLY_ROLE_MAPPING_METADATA_FLAG) instanceof Boolean readOnly
            ? readOnly
            : false;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "<" + name + " ; " + roles + "/" + roleTemplates + " = " + Strings.toString(expression) + ">";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final ExpressionRoleMapping that = (ExpressionRoleMapping) o;
        return this.enabled == that.enabled
            && Objects.equals(this.name, that.name)
            && Objects.equals(this.expression, that.expression)
            && Objects.equals(this.roles, that.roles)
            && Objects.equals(this.roleTemplates, that.roleTemplates)
            && Objects.equals(this.metadata, that.metadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, expression, roles, roleTemplates, metadata, enabled);
    }

    /**
     * Parse an {@link ExpressionRoleMapping} from the provided <em>XContent</em>
     */
    public static ExpressionRoleMapping parse(String name, BytesReference source, XContentType xContentType) throws IOException {
        try (
            XContentParser parser = XContentHelper.createParserNotCompressed(
                LoggingDeprecationHandler.XCONTENT_PARSER_CONFIG,
                source,
                xContentType
            )
        ) {
            return parse(name, parser);
        }
    }

    /**
     * Parse an {@link ExpressionRoleMapping} from the provided <em>XContent</em>
     */
    public static ExpressionRoleMapping parse(String name, XContentParser parser) throws IOException {
        try {
            final Builder builder = PARSER.parse(parser, name);
            return builder.build(name);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new ParsingException(parser.getTokenLocation(), e.getMessage(), e);
        }
    }

    /**
     * Converts this {@link ExpressionRoleMapping} into <em>XContent</em> that is compatible with
     *  the format handled by {@link #parse(String, BytesReference, XContentType)}.
     */
    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        return toXContent(builder, params, false);
    }

    public XContentBuilder toXContent(XContentBuilder builder, Params params, boolean indexFormat) throws IOException {
        builder.startObject();
        builder.field(Fields.ENABLED.getPreferredName(), enabled);
        if (roles.isEmpty() == false) {
            builder.startArray(Fields.ROLES.getPreferredName());
            for (String r : roles) {
                builder.value(r);
            }
            builder.endArray();
        }
        if (roleTemplates.isEmpty() == false) {
            builder.startArray(Fields.ROLE_TEMPLATES.getPreferredName());
            for (TemplateRoleName r : roleTemplates) {
                builder.value(r);
            }
            builder.endArray();
        }
        builder.field(Fields.RULES.getPreferredName());
        expression.toXContent(builder, params);

        builder.field(Fields.METADATA.getPreferredName(), metadata);

        if (indexFormat) {
            builder.field(NativeRoleMappingStoreField.DOC_TYPE_FIELD, NativeRoleMappingStoreField.DOC_TYPE_ROLE_MAPPING);
        }
        return builder.endObject();
    }

    public Set<String> getRoleNames(ScriptService scriptService, ExpressionModel model) {
        return Stream.concat(this.roles.stream(), this.roleTemplates.stream().flatMap(r -> r.getRoleNames(scriptService, model).stream()))
            .collect(Collectors.toSet());
    }

    /**
     * Used to facilitate the use of {@link ObjectParser} (via {@link #PARSER}).
     */
    private static class Builder {
        private RoleMapperExpression rules;
        private List<String> roles;
        private List<TemplateRoleName> roleTemplates;
        private Map<String, Object> metadata = Collections.emptyMap();
        private Boolean enabled;

        Builder rules(RoleMapperExpression expression) {
            this.rules = expression;
            return this;
        }

        Builder roles(List<String> roles) {
            this.roles = new ArrayList<>(roles);
            return this;
        }

        Builder roleTemplates(List<TemplateRoleName> templates) {
            this.roleTemplates = new ArrayList<>(templates);
            return this;
        }

        Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        private ExpressionRoleMapping build(String name) {
            if (roles == null && roleTemplates == null) {
                throw missingField(name, Fields.ROLES);
            }
            if (rules == null) {
                throw missingField(name, Fields.RULES);
            }
            if (enabled == null) {
                throw missingField(name, Fields.ENABLED);
            }
            return new ExpressionRoleMapping(name, rules, roles, roleTemplates, metadata, enabled);
        }

        private static IllegalStateException missingField(String id, ParseField field) {
            return new IllegalStateException("failed to parse role-mapping [" + id + "]. missing field [" + field + "]");
        }
    }

    public interface Fields {
        ParseField ROLES = new ParseField("roles");
        ParseField ROLE_TEMPLATES = new ParseField("role_templates");
        ParseField ENABLED = new ParseField("enabled");
        ParseField RULES = new ParseField("rules");
        ParseField METADATA = new ParseField("metadata");
    }
}
