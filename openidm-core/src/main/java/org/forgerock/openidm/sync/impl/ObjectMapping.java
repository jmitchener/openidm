/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions Copyrighted [year] [name of copyright owner]".
 *
 * Copyright © 2011 ForgeRock AS. All rights reserved.
 */

package org.forgerock.openidm.sync.impl;

// Java SE
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Collection;

// SLF4J
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// JSON Fluent
import org.forgerock.json.fluent.JsonValue;
import org.forgerock.json.fluent.JsonValueException;

// JSON Patch
import org.forgerock.json.patch.JsonPatch;
import org.forgerock.json.resource.JsonResourceContext;

// OpenIDM
import org.forgerock.openidm.objset.NotFoundException;
import org.forgerock.openidm.objset.ObjectSetContext;
import org.forgerock.openidm.objset.ObjectSetException;
import org.forgerock.openidm.repo.QueryConstants;
import org.forgerock.openidm.script.Script;
import org.forgerock.openidm.script.ScriptException;
import org.forgerock.openidm.script.Scripts;
import org.forgerock.openidm.sync.SynchronizationException;
import org.forgerock.openidm.sync.SynchronizationListener;

/**
 * TODO: Description.
 *
 * @author Paul C. Bryan
 * @author aegloff
 */
class ObjectMapping implements SynchronizationListener {

    /** TODO: Description. */
    private enum Status { SUCCESS, FAILURE }
    
    /** TODO: Description. */
    private final static Logger LOGGER = LoggerFactory.getLogger(ObjectMapping.class);

    /** TODO: Description. */
    private String name;

    /** The name of the links set to use. Defaults to mapping name. */
    private String linkTypeName;

    /** The link type to use */
    LinkType linkType;

    /** TODO: Description. */
    private String sourceObjectSet;

    /** TODO: Description. */
    private String targetObjectSet;

    /** TODO: Description. */
    private Script validSource;

    /** TODO: Description. */
    private Script validTarget;

    /** TODO: Description. */
    private Script correlationQuery;

    /** TODO: Description. */
    private ArrayList<PropertyMapping> properties = new ArrayList<PropertyMapping>();

    /** TODO: Description. */
    private ArrayList<Policy> policies = new ArrayList<Policy>();

    /** TODO: Description. */
    private Script onCreateScript;

    /** TODO: Description. */
    private Script onUpdateScript;

    /** TODO: Description. */
    private Script onDeleteScript;

    /** TODO: Description. */
    private Script onLinkScript;

    /** TODO: Description. */
    private Script onUnlinkScript;

    /** TODO: Description. */
    private Script resultScript;

    /** TODO: Description. */
    private SynchronizationService service;

    /** TODO: Description. */
    private ReconStats sourceStats = null;

    /** TODO: Description. */
    private ReconStats targetStats = null;

    /** TODO: Description. */
    private ReconStats globalStats = null;

    /**
     * TODO: Description.
     *
     * @param service
     * @param config TODO.
     * @throws JsonValueException TODO.
     */
    public ObjectMapping(SynchronizationService service, JsonValue config) throws JsonValueException {
        this.service = service;
        name = config.get("name").required().asString();
        linkTypeName = config.get("links").defaultTo(name).asString();
        sourceObjectSet = config.get("source").required().asString();
        targetObjectSet = config.get("target").required().asString();
        validSource = Scripts.newInstance("ObjectMapping", config.get("validSource"));
        validTarget = Scripts.newInstance("ObjectMapping", config.get("validTarget"));
        correlationQuery = Scripts.newInstance("ObjectMapping", config.get("correlationQuery"));
        for (JsonValue jv : config.get("properties").expect(List.class)) {
            properties.add(new PropertyMapping(service, jv));
        }
        for (JsonValue jv : config.get("policies").expect(List.class)) {
            policies.add(new Policy(service, jv));
        }
        onCreateScript = Scripts.newInstance("ObjectMapping", config.get("onCreate"));
        onUpdateScript = Scripts.newInstance("ObjectMapping", config.get("onUpdate"));
        onDeleteScript = Scripts.newInstance("ObjectMapping", config.get("onDelete"));
        onLinkScript = Scripts.newInstance("ObjectMapping", config.get("onLink"));
        onUnlinkScript = Scripts.newInstance("ObjectMapping", config.get("onUnlink"));
        resultScript = Scripts.newInstance("ObjectMapping", config.get("result"));
        LOGGER.debug("Instantiated {}", name);
    }
    
    public void initRelationships(SynchronizationService syncSvc, List<ObjectMapping> allMappings) {
        linkType = LinkType.getLinkType(this, allMappings);
    }

    /**
     * TODO: Description.
     * @return
     */
    SynchronizationService getService() {
        return service;
    }

    /**
     * @return The name of the object mapping
     */
    public String getName() {
        return name;
    }
    
    /**
     * @return The configured name of the link set to use for this object mapping
     */
    public String getLinkTypeName() {
        return linkTypeName;
    }
    
    /**
     * @return The resolved name of the link set to use for this object mapping
     */
    public LinkType getLinkType() {
        return linkType;
    }
    
    /**
     * @return The mapping source object set
     */
    public String getSourceObjectSet() {
        return sourceObjectSet;
    }
    
    /**
     * @return The mapping target object set
     */
    public String getTargetObjectSet() {
        return targetObjectSet;
    }
    
    /**
     * TODO: Description.
     *
     * @param id fully-qualified source object identifier.
     * @param value TODO.
     * @throws SynchronizationException TODO.
     */
    private void doSourceSync(String id, JsonValue value) throws SynchronizationException {
        LOGGER.trace("Start source synchronization of {} {}", id, (value == null ? "without a value" : "with a value"));
        String localId = id.substring(sourceObjectSet.length() + 1); // skip the slash
// TODO: one day bifurcate this for synchronous and asynchronous source operation
        SourceSyncOperation op = new SourceSyncOperation();
        op.sourceId = localId;
        if (value != null) {
            op.sourceObject = value;
            op.sourceObject.put("_id", localId); // unqualified
        }
        op.sync();
    }

    /**
     * TODO: Description.
     *
     * @param query TODO.
     * @return TODO.
     * @throws SynchronizationException TODO.
     */
    private Map<String, Object> queryTargetObjectSet(Map<String, Object> query) throws SynchronizationException {
        try {
            return service.getRouter().query(targetObjectSet, query);
        } catch (ObjectSetException ose) {
            throw new SynchronizationException(ose);
        }
    }

    /**
     * TODO: Description.
     *
     * @param objectSet TODO.
     * @return TODO.
     * @throws org.forgerock.openidm.sync.SynchronizationException
     */
// TODO: Codify query-all-ids in ObjectSet or provide per-ObjectSet query for all IDs.
    private Iterable<String> queryAllIds(final String objectSet) throws SynchronizationException {
        return new Iterable<String>() {

            JsonValue list;

            {
                HashMap<String, Object> query = new HashMap<String, Object>();
                query.put(QueryConstants.QUERY_ID, "query-all-ids");
                try {
                    list = new JsonValue(service.getRouter().query(objectSet, query)).get(QueryConstants.QUERY_RESULT).required().expect(List.class);
                } catch (JsonValueException jve) {
                    throw new SynchronizationException(jve);
                } catch (ObjectSetException ose) {
                    throw new SynchronizationException(ose);
                }
            }

            @Override
            public Iterator<String> iterator() {
                final Iterator<JsonValue> iterator = list.iterator();
                return new Iterator<String>() {

                    @Override
                    public boolean hasNext() {
                        return iterator.hasNext();
                    }

                    @Override
                    public String next() { // throws JsonValueException
                        return iterator.next().get("_id").asString();
                    }

                    @Override
                    public void remove() {
                        throw new UnsupportedOperationException();
                    }
                };
            }
        };
    }

    /**
     * TODO: Description.
     *
     * @param id TODO.
     * @throws NullPointerException if {@code targetId} is {@code null}.
     * @throws SynchronizationException TODO.
     * @return
     */
    private JsonValue readObject(String id) throws SynchronizationException {
        if (id == null) {
            throw new NullPointerException();
        }
        try {
            return new JsonValue(service.getRouter().read(id));
        } catch (NotFoundException nfe) { // target not found results in null
            return null;
        } catch (ObjectSetException ose) {
            LOGGER.warn("Failed to read target object", ose);
            throw new SynchronizationException(ose);
        }
    }

// TODO: maybe move all this target stuff into a target object wrapper to keep this class clean
    /**
     * TODO: Description.
     *
     * @param target TODO.
     * @throws SynchronizationException TODO.
     */
    private void createTargetObject(JsonValue target) throws SynchronizationException {
        StringBuilder sb = new StringBuilder();
        sb.append(targetObjectSet);
        if (target.get("_id").isString()) {
            sb.append('/').append(target.get("_id").asString());
        }
        String id = sb.toString();
        LOGGER.trace("Create target object {}", id);
        try {
            service.getRouter().create(id, target.asMap());
        } catch (JsonValueException jve) {
            throw new SynchronizationException(jve);
        } catch (ObjectSetException ose) {
            LOGGER.warn("Failed to create target object", ose);
            throw new SynchronizationException(ose);
        }
    }

    /**
     * TODO: Description.
     *
     * @param objSet TODO.
     * @param objId TODO.
     * @return TODO.
     */
    private String qualifiedId(String objSet, String objId) {
        StringBuilder sb = new StringBuilder();
        sb.append(objSet);
        if (objId != null) {
            sb.append('/').append(objId);
        }
        return sb.toString();
    }

// TODO: maybe move all this target stuff into a target object wrapper to keep this class clean
    /**
     * TODO: Description.
     *
     * @param target TODO.
     * @throws SynchronizationException TODO.
     */
    private void updateTargetObject(JsonValue target) throws SynchronizationException {
        try {
            String id = qualifiedId(targetObjectSet, target.get("_id").required().asString());
            LOGGER.trace("Update target object {}", id);
            service.getRouter().update(id, target.get("_rev").asString(), target.asMap());
        } catch (JsonValueException jve) {
            throw new SynchronizationException(jve);
        } catch (ObjectSetException ose) {
            LOGGER.warn("Failed to update target object", ose);
            throw new SynchronizationException(ose);
        }
    }

// TODO: maybe move all this target stuff into a target object wrapper to keep this class clean
    /**
     * TODO: Description.
     *
     * @param target TODO.
     * @throws SynchronizationException TODO.
     */
    private void deleteTargetObject(JsonValue target) throws SynchronizationException {
        if (target.get("_id").isString()) { // forgiving delete
            try {
                String id = qualifiedId(targetObjectSet, target.get("_id").required().asString());
                LOGGER.trace("Delete target object {}", id);
                service.getRouter().delete(id, target.get("_rev").asString());
            } catch (JsonValueException jve) {
                throw new SynchronizationException(jve);
            } catch (NotFoundException nfe) {
                // forgiving delete
            } catch (ObjectSetException ose) {
                LOGGER.warn("Failed to delete target object", ose);
                throw new SynchronizationException(ose);
            }
        }
    }

    /**
     * TODO: Description.
     *
     * @param source TODO.
     * @param target TODO.
     * @throws SynchronizationException TODO.
     */
    private void applyMappings(JsonValue source, JsonValue target) throws SynchronizationException {
        for (PropertyMapping property : properties) {
            property.apply(source, target);
        }
    }

    /**
     * Returns {@code true} if the specified object identifer is in this mapping's source
     * object set.
     */
    private boolean isSourceObject(String id) {
        return (id.startsWith(sourceObjectSet + '/') && id.length() > sourceObjectSet.length() + 1);
    }

    @Override
    public void onCreate(String id, JsonValue value) throws SynchronizationException {
        if (isSourceObject(id)) {
            if (value == null || value.getObject() == null) { // notification without the actual value
                value = readObject(id);
            }
            doSourceSync(id, value); // synchronous for now
        }
    }

    @Override
    public void onUpdate(String id, JsonValue oldValue, JsonValue newValue) throws SynchronizationException {
        if (isSourceObject(id)) {
            if (newValue == null || newValue.getObject() == null) { // notification without the actual value
                newValue = readObject(id);
            }
            // TODO: use old value to project incremental diff without fetch of source
            if (oldValue == null || oldValue.getObject() == null || JsonPatch.diff(oldValue, newValue).size() > 0) {
                doSourceSync(id, newValue); // synchronous for now
            } else {
                LOGGER.trace("There is nothing to update on {}", id);
            }
        }
    }

    @Override
    public void onDelete(String id) throws SynchronizationException {
        if (isSourceObject(id)) {
            doSourceSync(id, null); // synchronous for now
        }
    }

    public void recon(String reconId) throws SynchronizationException {
        doRecon(reconId);
    }

    private void doResults() throws SynchronizationException {
        if (resultScript != null) {
            Map<String, Object> scope = service.newScope();
            scope.put("source", sourceStats.asMap());
            scope.put("target", targetStats.asMap());
            scope.put("global", globalStats.asMap());
            try {
                resultScript.exec(scope);
            } catch (ScriptException se) {
                LOGGER.debug("{} result script encountered exception", name, se);
                throw new SynchronizationException(se);
            }
        }
    }

    /**
     * TEMPORARY. Future version will have this break-down into discrete units of work.
     * @param reconId
     * @throws org.forgerock.openidm.sync.SynchronizationException
     */
    private void doRecon(String reconId) throws SynchronizationException {
        sourceStats = new ReconStats(reconId,sourceObjectSet);
        globalStats = new ReconStats(reconId,name);

        sourceStats.startAllIds();
        Iterator<String> i = queryAllIds(sourceObjectSet).iterator();
        sourceStats.endAllIds();
        if (!i.hasNext()) {
            throw new SynchronizationException("Cowardly refusing to perform reconciliation with an empty source object set");
        }
        while (i.hasNext()) {
            String sourceId = i.next();
            SourceSyncOperation op = new SourceSyncOperation();
            ReconEntry entry = new ReconEntry(op);
            op.sourceId = sourceId;
            entry.sourceId = qualifiedId(sourceObjectSet, sourceId);
            sourceStats.entries++;
            op.reconId = reconId;
            try {
                op.sourceObject = readObject(entry.sourceId);
                op.sync();
            } catch (SynchronizationException se) {
                if (op.action != Action.EXCEPTION) {
                    entry.status = Status.FAILURE; // exception was not intentional
                    LOGGER.warn("Unexpected failure during source reconciliation {}", reconId, se);
                }
                Throwable throwable = se;
                while (throwable.getCause() != null) { // want message associated with original cause
                    throwable = throwable.getCause();
                }
                if (se != throwable) {
                    entry.message = se.getMessage() + ". Root cause: " + throwable.getMessage();
                } else {
                    entry.message = throwable.getMessage();
                }
            }
            if (entry.status == Status.FAILURE || op.action != null) {
                entry.timestamp = new Date();
                entry.reconciling = "source";
                if (op.targetObject != null) {
                    entry.targetId = qualifiedId(targetObjectSet, op.targetObject.get("_id").asString());
                }
                logReconEntry(entry);
            }
        }
        sourceStats.end();
        targetStats = new ReconStats(reconId,targetObjectSet);
        targetStats.startAllIds();
        Iterator<String> j = queryAllIds(targetObjectSet).iterator();
        targetStats.endAllIds();

        while (j.hasNext()) {
            String targetId = j.next();
            TargetSyncOperation op = new TargetSyncOperation();
            ReconEntry entry = new ReconEntry(op);
            entry.targetId = qualifiedId(targetObjectSet, targetId);
            targetStats.entries++;
            op.reconId = reconId;
            try {
                op.targetObject = readObject(entry.targetId);
                op.sync();
            } catch (SynchronizationException se) {
                if (op.action != Action.EXCEPTION) {
                    entry.status = Status.FAILURE; // exception was not intentional
                    LOGGER.warn("Unexpected failure during target reconciliation {}", reconId, se);
                }
                Throwable throwable = se;
                while (throwable.getCause() != null) { // want message associated with original cause
                    throwable = throwable.getCause();
                }
                if (se != throwable) {
                    entry.message = se.getMessage() + ". Root cause: " + throwable.getMessage();
                } else {
                    entry.message = throwable.getMessage();
                }
            }
            if (entry.status == Status.FAILURE || op.action != null) {
                entry.timestamp = new Date();
                entry.reconciling = "target";
                if (op.sourceObject != null) {
                    entry.sourceId = qualifiedId(sourceObjectSet, op.sourceObject.get("_id").asString());
                }
                logReconEntry(entry);
            }
        }
        targetStats.end();
        globalStats.end();
        doResults();
// TODO: cleanup orphan link objects (no matching source or target) here 
    }

    /**
     * TODO: Description.
     *
     * @param entry TODO.
     * @throws SynchronizationException TODO.
     */
    private void logReconEntry(ReconEntry entry) throws SynchronizationException {
        try {
            service.getRouter().create("audit/recon", entry.toJsonValue().asMap());
        } catch (ObjectSetException ose) {
            throw new SynchronizationException(ose);
        }
    }
    
    /*
     * Execute a sync engine action explicitly, without going through situation assessment.
     * @param sourceObject the source object if applicable to the action
     * @param targetObject the target object if applicable to the action
     * @param situation an optional situation that was originally assessed. Null if not the result of an earlier situation assessment.
     * @param action the explicit action to invoke
     * @param reconId an optional identifier for the recon context if this is done in the context of reconciliation
     */
    public void explicitOp(JsonValue sourceObject, JsonValue targetObject, Situation situation, Action action, String reconId) 
            throws SynchronizationException {
        ExplicitSyncOperation linkOp = new ExplicitSyncOperation();
        linkOp.init(sourceObject, targetObject, situation, action, reconId);
        linkOp.sync();
    }

    /**
     * TODO: Description.
     */
    private abstract class SyncOperation {

        /** TODO: Description. */
        public String reconId;
        /** TODO: Description. */
        public JsonValue sourceObject;
        /** TODO: Description. */
        public JsonValue targetObject;
        /** TODO: Description. */
        public final Link linkObject = new Link(ObjectMapping.this);
        /** TODO: Description. */
        public Situation situation;
        /** TODO: Description. */
        public Action action;

        /**
         * TODO: Description.
         *
         * @throws SynchronizationException TODO.
         */
        public abstract void sync() throws SynchronizationException;

        /**
         * TODO: Description.
         * @param sourceAction sourceAction true if the {@link Action} is determined for the {@link SourceSyncOperation}
         * and false if the action is determined for the {@link TargetSyncOperation}.
         * @throws SynchronizationException TODO.
         */
        protected void determineAction(boolean sourceAction) throws SynchronizationException {
            if (situation != null) {
                action = situation.getDefaultAction(); // start with a reasonable default
                for (Policy policy : policies) {
                    if (situation == policy.getSituation()) {
                        action = policy.getAction(sourceObject, targetObject, sourceAction);
// TODO: Consider limiting what actions can be returned for the given situation.
                        break;
                    }
                }
            }
            LOGGER.debug("Determined action to be {}", action);
        }

        /**
         * TODO: Description.
         *
         * @throws SynchronizationException TODO.
         */
        @SuppressWarnings("fallthrough")
        protected void performAction() throws SynchronizationException {
            Action action = (this.action == null ? Action.IGNORE : this.action);
            try {
                switch (action) {
                    case CREATE:
                        if (sourceObject == null) {
                            throw new SynchronizationException("no source object to create target from");
                        }
                        if (targetObject != null) {
                            throw new SynchronizationException("target object already exists");
                        }
                        targetObject = new JsonValue(new HashMap<String, Object>());
                        applyMappings(sourceObject, targetObject); // apply property mappings to target
                        execScript("onCreate", onCreateScript);
                        
                        JsonValue context = ObjectSetContext.get();
                        // Allow the early link creation as soon as the target identifier is known
                        String sourceId = sourceObject.get("_id").required().asString();
                        PendingLink.populate(context, ObjectMapping.this.name, sourceId, sourceObject, reconId, situation);
                        
                        createTargetObject(targetObject);
                        boolean wasLinked = PendingLink.wasLinked(context);
                        if (wasLinked) {
                            LOGGER.debug("Pending link for {} during {} has already been created, skipping additional link processing",
                                    sourceId, reconId);
                            break;
                        } else {
                            LOGGER.debug("Pending link for {} during {} not yet resolved, proceed to link processing", sourceId, reconId);
                            PendingLink.clear(context); // We'll now handle link creation ourselves
                        }
                    // falls through to link the newly created target
                    case UPDATE:
                    case LINK:
                        if (targetObject == null) {
                            throw new SynchronizationException("no target object to link");
                        }
                        if (linkObject._id == null) {
                            // try to read again as it may have been created in a cascade
                            linkObject.getLinkForSource(sourceObject.get("_id").required().asString());
                        }

                        String targetId = targetObject.get("_id").required().asString();
                        if (linkObject._id == null) {
                            createLink(sourceObject.get("_id").required().asString(), targetId, reconId);
                        } else if (!targetId.equals(linkObject.targetId) || (reconId != null && !reconId.equals(linkObject.reconId))) {
                            linkObject.targetId = targetId;
                            linkObject.reconId = reconId;
                            linkObject.update();
                        }
// TODO: Detect change of source id, and update link accordingly.
                        if (action == Action.CREATE || action == Action.LINK) {
                            break; // do not update target
                        }
                        if (sourceObject != null && targetObject != null) {
                            JsonValue oldTarget = targetObject.copy();
                            applyMappings(sourceObject, targetObject);
                            execScript("onUpdate", onUpdateScript);
                            if (JsonPatch.diff(oldTarget, targetObject).size() > 0) { // only update if target changes
                                updateTargetObject(targetObject);
                            }
                        }
                        break; // terminate UPDATE
                    case DELETE:
                        if (targetObject != null) { // forgiving; does nothing if no target
                            execScript("onDelete", onDeleteScript);
                            deleteTargetObject(targetObject);
                            targetObject = null;
                        }
                    // falls through to unlink the deleted target
                    case UNLINK:
                        if (linkObject._id != null) { // forgiving; does nothing if no link exists
                            execScript("onUnlink", onUnlinkScript);
                            linkObject.delete();
                        }
                        break; // terminate DELETE and UNLINK
                    case EXCEPTION:
                        throw new SynchronizationException(); // aborts change; recon reports
                }
            } catch (JsonValueException jve) {
                throw new SynchronizationException(jve);
            }
        }
        
        protected void createLink(String sourceId, String targetId, String reconId) throws SynchronizationException {
            Link linkObject = new Link(ObjectMapping.this);
            execScript("onLink", onLinkScript);
            linkObject.sourceId = sourceId;
            linkObject.targetId = targetId;
            linkObject.reconId = reconId;
            linkObject.create();
            LOGGER.debug("Established link sourceId: {} targetId: {} in reconId: {}", new Object[] {sourceId, targetId, reconId});
        }

        /**
         * TODO: Description.
         *
         * @return TODO.
         * @throws SynchronizationException TODO.
         */
        protected boolean isSourceValid() throws SynchronizationException {
            boolean result = false;
            if (sourceObject != null) { // must have a source object to be valid
                if (validSource != null) {
                    Map<String, Object> scope = service.newScope();
                    scope.put("source", sourceObject.asMap());
                    try {
                        Object o = validSource.exec(scope);
                        if (o == null || !(o instanceof Boolean)) {
                            throw new SynchronizationException("Expecting boolean value from validSource");
                        }
                        result = (Boolean) o;
                    } catch (ScriptException se) {
                        LOGGER.debug("{} validSource script encountered exception", name, se);
                        throw new SynchronizationException(se);
                    }
                } else { // no script means true
                    result = true;
                }
            }
            LOGGER.trace("isSourceValid of {} evaluated: {}", null != sourceObject ? sourceObject.get("_id").getObject() : "[NULL]", result);
            return result;
        }

        /**
         * TODO: Description.
         *
         * @return TODO.
         * @throws SynchronizationException TODO.
         */
        protected boolean isTargetValid() throws SynchronizationException {
            boolean result = false;
            if (targetObject != null) { // must have a target object to qualify
                if (validTarget != null) {
                    Map<String, Object> scope = service.newScope();
                    scope.put("target", targetObject.asMap());
                    try {
                        Object o = validTarget.exec(scope);
                        if (o == null || !(o instanceof Boolean)) {
                            throw new SynchronizationException("Expecting boolean value from validTarget");
                        }
                        result = (Boolean) o;
                    } catch (ScriptException se) {
                        LOGGER.debug("{} validTarget script encountered exception", name, se);
                        throw new SynchronizationException(se);
                    }
                } else { // no script means true
                    result = true;
                }
            }
            LOGGER.trace("isTargetValid of {} evaluated: {}", null != targetObject ? targetObject.get("_id").getObject() : "[NULL]", result);
            return result;
        }
        
        /**
         * Executes the given script with the appropriate context information
         *
         * @param type The script hook name
         * @param script The script to execute
         * @throws SynchronizationException TODO.
         */
        private void execScript(String type, Script script) throws SynchronizationException {
            if (script != null) {
                Map<String, Object> scope = service.newScope();
                if (sourceObject != null) {
                    scope.put("source", sourceObject.asMap());
                }
                if (targetObject != null) {
                    scope.put("target", targetObject.asMap());
                }
                if (situation != null) {
                    scope.put("situation", situation.toString());
                }
                try {
                    script.exec(scope);
                } catch (ScriptException se) {
                    LOGGER.debug("{} script encountered exception", name + " " + type, se);
                    throw new SynchronizationException(se);
                }
            }
        }
    }

    /**
     * Explicit execution of a sync operation where the appropriate 
     * action is known without having to assess the situation and apply 
     * policy to decide the action
     */
    private class ExplicitSyncOperation extends SyncOperation {

        public void init(JsonValue sourceObject, JsonValue targetObject, Situation situation, Action action, String reconId) {
            this.reconId = reconId;
            this.sourceObject = sourceObject;
            this.targetObject = targetObject;
            this.situation = situation;
            this.action = action;
        }
        
        @Override
        public void sync() throws SynchronizationException {
            LOGGER.debug("Initiate explicit operation call for situation: {}, action: {}", situation, action);
            performAction();
            LOGGER.debug("Complected explicit operation call for situation: {}, action: {}", situation, action);
        }
    }
    
    /**
     * TODO: Description.
     */
    private class SourceSyncOperation extends SyncOperation {

        /** TODO: Description. */
        public String sourceId;

        @Override
        public void sync() throws SynchronizationException {
            assessSituation();
            determineAction(true);
            performAction();
        }

        /**
         * TODO: Description.
         *
         * @throws SynchronizationException TODO.
         */
        private void assessSituation() throws SynchronizationException {
            situation = null;
            if (sourceId != null) {
                linkObject.getLinkForSource(sourceId);
            }
            if (linkObject._id != null) {
                targetObject = readObject(qualifiedId(targetObjectSet, linkObject.targetId));
            }
            if (isSourceValid()) { // source is valid for mapping
                if (linkObject._id != null) { // source object linked to target
                    if (targetObject != null) {
                        situation = Situation.CONFIRMED;
                    } else {
                        situation = Situation.MISSING;
                    }
                } else { // source object not linked to target
                    JsonValue results = correlateTarget();
                    if (results == null) { // no correlationQuery defined
                        situation = Situation.ABSENT;
                    } else if (results.size() == 1) {
                        //TODO Optimize to get the entire object with one query if it's sufficeient
                        JsonValue resultValue = results.get((Integer) 0).required();
                        if (hasNonSpecialAttribute(resultValue.keys())) { //Assume this is a full object
                            targetObject = resultValue;
                        } else {
                            targetObject = readObject(qualifiedId(targetObjectSet,
                                    resultValue.get("_id").required().asString()));
                        }
                        situation = Situation.FOUND;
                    } else if (results.size() == 0) {
                        situation = Situation.ABSENT;
                    } else {
                        situation = Situation.AMBIGUOUS;
                    }
                }
            } else { // mapping does not qualify for target
                if (linkObject._id != null) {
                    situation = Situation.UNQUALIFIED;
                } else {
                    /*JsonValue results = correlateTarget();
                    if (null != results && results.size() == 1) {
                    targetObject = readObject(qualifiedId(targetObjectSet,
                    results.get((Integer) 0).required().get("_id").required().asString()));
                    situation = Situation.UNASSIGNED;
                    } else {*/
                    situation = null; // TODO: provide a situation for this?
                    /*}*/
                }
                if (sourceStats != null) {
                    sourceStats.addNotValid(sourceId);
                }
            }
            if (sourceStats != null){
                sourceStats.addSituation(sourceId, situation);
            }
            LOGGER.debug("Mapping '{}' assessed situation of {} to be {}", new Object[]{name, sourceId, situation});
        }

        /**
         * TODO: Description.
         *
         * @return TODO.
         * @throws SynchronizationException TODO.
         */
        @SuppressWarnings("unchecked")
        private JsonValue correlateTarget() throws SynchronizationException {
            JsonValue result = null;
            if (correlationQuery != null) {
                Map<String, Object> queryScope = service.newScope();
                queryScope.put("source", sourceObject.asMap());
                try {
                    Object query = correlationQuery.exec(queryScope);
                    if (query == null || !(query instanceof Map)) {
                        throw new SynchronizationException("Expected correlationQuery script to yield a Map");
                    }
                    result = new JsonValue(queryTargetObjectSet((Map)query)).get(QueryConstants.QUERY_RESULT).required();
                } catch (ScriptException se) {
                    LOGGER.debug("{} correlationQuery script encountered exception", name, se);
                    throw new SynchronizationException(se);
                }
            }
            return result;
        }

        /**
         * Primitive implementation to decide if the object is a "full" or a partial.
         *
         * @param keys attribute names of object
         * @return true if the {@code keys} has value not starting with "_" char
         */
        private boolean hasNonSpecialAttribute(Collection<String> keys) {
            for (String attr : keys) {
                if (!attr.startsWith("_")) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * TODO: Description.
     */
    private class TargetSyncOperation extends SyncOperation {

        @Override
        public void sync() throws SynchronizationException {
            assessSituation();
            determineAction(false);
// TODO: Option here to just report what action would be performed?
            performAction();
        }

        /**
         * TODO: Description.
         *
         * @throws SynchronizationException TODO.
         */
        private void assessSituation() throws SynchronizationException {
            situation = null;
            String targetId = (targetObject != null ? targetObject.get("_id").asString() : null);
            if (!isTargetValid()) { // target is not valid for this mapping; ignore it
                if (targetStats != null) {
                    targetStats.addNotValid(targetId);
                }
                return;
            }
            if (targetId != null) {
                linkObject.getLinkForTarget(targetId);
            }
            if (reconId != null && reconId.equals(linkObject.reconId)) {
                return; // optimization: already handled in previous phase; ignore it
            } else if (linkObject._id == null || linkObject.sourceId == null) {
                situation = Situation.UNASSIGNED;
            } else {
                sourceObject = readObject(qualifiedId(sourceObjectSet, linkObject.sourceId));
                if (sourceObject == null || !isSourceValid()) {
                    situation = Situation.UNQUALIFIED;
                } else { // proper link
                    situation = Situation.CONFIRMED;
                }
            }
            if (targetStats != null){
                targetStats.addSituation(targetId, situation);
            }
        }
    }

    /**
     * TEMPORARY.
     */
    private class ReconEntry {

        /** TODO: Description. */
        public final SyncOperation op;
        /** TODO: Description. */
        public Date timestamp;
        /** TODO: Description. */
        public Status status = ObjectMapping.Status.SUCCESS;
        /** TODO: Description. */
        public String sourceId;
        /** TODO: Description. */
        public String targetId;
        /** TODO: Description. */
        public String reconciling;
        /** TODO: Description. */
        public String message;
// TODO: replace with proper formatter
        SimpleDateFormat isoFormatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");

        public ReconEntry(SyncOperation op) {
            this.op = op;
        }

        /**
         * TODO: Description.
         *
         * @return TODO.
         */
        private JsonValue toJsonValue() {
            JsonValue jv = new JsonValue(new HashMap<String, Object>());
            jv.put("reconId", op.reconId);
            jv.put("reconciling", reconciling);
            jv.put("sourceObjectId", sourceId);
            jv.put("targetObjectId", targetId);
            jv.put("timestamp", isoFormatter.format(timestamp));
            jv.put("situation", (op.situation == null ? null : op.situation.toString()));
            jv.put("action", (op.action == null ? null : op.action.toString()));
            jv.put("status", (status == null ? null : status.toString()));
            jv.put("message", message);
            return jv;
        }
    }
}
