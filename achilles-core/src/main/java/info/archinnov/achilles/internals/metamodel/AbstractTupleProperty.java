/*
 * Copyright (C) 2012-2015 DuyHai DOAN
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

package info.archinnov.achilles.internals.metamodel;


import static java.lang.String.format;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.datastax.driver.core.SettableData;
import com.datastax.driver.core.TupleType;
import com.datastax.driver.core.TupleValue;
import com.datastax.driver.core.UDTValue;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.reflect.TypeToken;

import info.archinnov.achilles.internals.factory.TupleTypeFactory;
import info.archinnov.achilles.internals.factory.UserTypeFactory;
import info.archinnov.achilles.internals.injectable.InjectTupleTypeFactory;
import info.archinnov.achilles.internals.metamodel.columns.FieldInfo;
import info.archinnov.achilles.type.factory.BeanFactory;
import info.archinnov.achilles.type.tuples.Tuple;

public abstract class AbstractTupleProperty<ENTITY, T extends Tuple> extends AbstractProperty<ENTITY, T, TupleValue>
        implements InjectTupleTypeFactory {

    public static final TypeToken<TupleValue> TUPLE_VALUE_TYPE_TOKEN = new TypeToken<TupleValue>() {
    };
    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractTupleProperty.class);
    protected TupleType tupleType;
    protected TupleTypeFactory tupleTypeFactory;

    AbstractTupleProperty(TypeToken<T> valueFromTypeToken, FieldInfo<ENTITY, T> fieldInfo) {
        super(valueFromTypeToken, TUPLE_VALUE_TYPE_TOKEN, fieldInfo);
    }

    @Override
    public abstract TupleType buildType();

    protected abstract List<AbstractProperty<ENTITY, ?, ?>> componentsProperty();

    @Override
    public void encodeToSettable(TupleValue tuple, SettableData<?> settableData) {
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace(format("Encode tuple value %s to settable object %s", tuple, settableData));
        }
        settableData.setTupleValue(fieldInfo.cqlColumn, tuple);
    }

    @Override
    public void encodeFieldToUdt(ENTITY entity, UDTValue udtValue) {
        final TupleValue tupleValue = encodeField(entity);
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace(format("Encode tuple value %s to udt value %s", tupleValue, udtValue));
        }
        udtValue.setTupleValue(fieldInfo.cqlColumn, tupleValue);
    }

    @Override
    public void inject(TupleTypeFactory factory) {
        tupleTypeFactory = factory;
        tupleType = buildType();

        for (AbstractProperty<ENTITY, ?, ?> x : componentsProperty()) {
            x.inject(factory);
        }
    }

    @Override
    public void inject(UserTypeFactory factory) {
        for (AbstractProperty<ENTITY, ?, ?> x : componentsProperty()) {
            x.inject(factory);
        }
    }

    @Override
    public void inject(ObjectMapper jacksonMapper) {
        for (AbstractProperty<ENTITY, ?, ?> x : componentsProperty()) {
            x.inject(jacksonMapper);
        }
    }


    @Override
    public void inject(BeanFactory factory) {
        for (AbstractProperty<ENTITY, ?, ?> x : componentsProperty()) {
            x.inject(factory);
        }
    }

}