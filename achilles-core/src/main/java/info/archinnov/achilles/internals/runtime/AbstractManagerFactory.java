/*
 * Copyright (C) 2012-2016 DuyHai DOAN
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package info.archinnov.achilles.internals.runtime;

import static info.archinnov.achilles.internals.schema.SchemaCreator.generateSchemaAtRuntime;
import static info.archinnov.achilles.internals.schema.SchemaCreator.generateUDTAtRuntime;
import static java.lang.String.format;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.datastax.driver.core.*;
import com.datastax.driver.extras.codecs.arrays.DoubleArrayCodec;
import com.datastax.driver.extras.codecs.arrays.FloatArrayCodec;
import com.datastax.driver.extras.codecs.arrays.IntArrayCodec;
import com.datastax.driver.extras.codecs.arrays.LongArrayCodec;
import com.datastax.driver.extras.codecs.jdk8.InstantCodec;
import com.datastax.driver.extras.codecs.jdk8.LocalDateCodec;
import com.datastax.driver.extras.codecs.jdk8.LocalTimeCodec;
import com.datastax.driver.extras.codecs.jdk8.ZonedDateTimeCodec;

import info.archinnov.achilles.internals.context.ConfigurationContext;
import info.archinnov.achilles.internals.factory.TupleTypeFactory;
import info.archinnov.achilles.internals.factory.UserTypeFactory;
import info.archinnov.achilles.internals.metamodel.AbstractEntityProperty;
import info.archinnov.achilles.internals.metamodel.AbstractUDTClassProperty;
import info.archinnov.achilles.internals.metamodel.UDTProperty;

public abstract class AbstractManagerFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractManagerFactory.class);

    protected final Cluster cluster;
    protected final ConfigurationContext configContext;
    protected final RuntimeEngine rte;

    protected List<AbstractEntityProperty<?>> entityProperties;
    protected List<Class<?>> entityClasses;

    public AbstractManagerFactory(Cluster cluster, ConfigurationContext configContext) {
        this.cluster = cluster;
        this.configContext = configContext;
        this.rte = new RuntimeEngine(configContext);
    }

    protected abstract List<AbstractUDTClassProperty<?>> getUdtClassProperties();

    /**
     * Provide the statically computed table name with keyspace (if defined) for a given entity class
     *
     * @param entityClass given entity class
     * @return statically computed table name with keyspace (if define)
     */
    public Optional<String> staticTableNameFor(Class<?> entityClass) {

        final Optional<String> tableName = entityProperties
                .stream()
                .filter(x -> x.entityClass.equals(entityClass))
                .map(x -> x.getKeyspace().map(ks -> ks + "." + x.getTableName()).orElse(x.getTableName()))
                .findFirst();

        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace(format("Determining table name for entity type %s : %s",
                    entityClass.getCanonicalName(), tableName));
        }

        return tableName;
    }

    /**
     * Shutdown the manager factory and the related session and executor service (if they are created by Achilles).
     * If the Java driver Session object and/or the executor service were provided as bootstrap parameter, Achilles
     * will <strong>NOT</strong> shut them down. This should be handled externally
     */
    @PreDestroy
    public void shutDown() {
        LOGGER.info("Calling shutdown on ManagerFactory");

        if (!configContext.isProvidedSession()) {
            LOGGER.info(format("Closing built Session object %s", rte.session));
            rte.session.close();
        }
        if (!configContext.isProvidedExecutorService()) {
            LOGGER.info(format("Closing built executor service (thread pool) %s", configContext.getExecutorService()));
            configContext.getExecutorService().shutdown();
        }
    }

    protected void bootstrap() {
        addNativeCodecs();
        injectDependencies();
        createSchema();
        validateSchema();
        prepareStaticStatements();
    }

    protected void addNativeCodecs() {
        LOGGER.trace("Add Java Driver extra codecs");
        final Configuration configuration = cluster.getConfiguration();
        final TupleType zonedDateTimeType = TupleType.of(configuration.getProtocolOptions().getProtocolVersion(), configuration.getCodecRegistry(),
                DataType.timestamp(), DataType.varchar());

        final CodecRegistry codecRegistry = configuration.getCodecRegistry();
        codecRegistry.register(DoubleArrayCodec.instance, FloatArrayCodec.instance,
                IntArrayCodec.instance, LongArrayCodec.instance,
                InstantCodec.instance, LocalDateCodec.instance,
                LocalTimeCodec.instance, new ZonedDateTimeCodec(zonedDateTimeType));

    }

    protected void injectDependencies() {
        final CodecRegistry codecRegistry = cluster.getConfiguration().getCodecRegistry();
        final ProtocolVersion protocolVersion = cluster.getConfiguration().getProtocolOptions().getProtocolVersion();
        final TupleTypeFactory tupleTypeFactory = new TupleTypeFactory(protocolVersion, codecRegistry);
        final UserTypeFactory userTypeFactory = new UserTypeFactory(protocolVersion, codecRegistry);
        rte.tupleTypeFactory = tupleTypeFactory;
        rte.userTypeFactory = userTypeFactory;
        final List<Class<?>> manageEntities = configContext.getManageEntities().isEmpty() ? entityClasses : configContext.getManageEntities();
        entityProperties
                .stream()
                .filter(x -> manageEntities.contains(x.entityClass))
                .forEach(x -> configContext.injectDependencies(tupleTypeFactory, userTypeFactory, x));
    }

    protected void validateSchema() {
        final List<Class<?>> manageEntities = configContext.getManageEntities().isEmpty() ? entityClasses : configContext.getManageEntities();
        entityProperties
                .stream()
                .filter(x -> manageEntities.contains(x.entityClass))
                .forEach(x -> x.validateSchema(configContext));
    }


    protected void createSchema() {
        final Session session = configContext.getSession();
        final List<Class<?>> manageEntities = configContext.getManageEntities().isEmpty() ? entityClasses : configContext.getManageEntities();
        if (configContext.isForceSchemaGeneration()) {

            for (AbstractUDTClassProperty<?> x : getUdtClassProperties()) {
                final long udtCountForClass = entityProperties
                        .stream()
                        .filter(entityProperty -> manageEntities.contains(entityProperty.entityClass))
                        .flatMap(entityProperty -> entityProperty.allColumns.stream())
                        .filter(property -> property instanceof UDTProperty)
                        .filter(property -> ((UDTProperty) property).udtClassProperty.equals(x))
                        .count();

                if(udtCountForClass>0)
                    generateUDTAtRuntime(session, x);
            }

            entityProperties
                    .stream()
                    .filter(x -> manageEntities.contains(x.entityClass))
                    .forEach(x -> generateSchemaAtRuntime(session, x));

        }
    }

    protected void prepareStaticStatements() {
        final List<Class<?>> manageEntities = configContext.getManageEntities().isEmpty() ? entityClasses : configContext.getManageEntities();
        entityProperties
                .stream()
                .filter(x -> manageEntities.contains(x.entityClass))
                .forEach(x -> x.prepareStaticStatements(configContext.getSession(), rte.cache));
    }


}
