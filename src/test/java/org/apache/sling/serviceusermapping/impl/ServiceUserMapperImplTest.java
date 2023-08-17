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
package org.apache.sling.serviceusermapping.impl;

import org.apache.sling.serviceusermapping.ServicePrincipalsValidator;
import org.apache.sling.serviceusermapping.ServiceUserValidator;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.stream.StreamSupport;

import static org.apache.sling.serviceusermapping.ServiceUserMapper.VALIDATOR_ID;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

public class ServiceUserMapperImplTest {
    private static final String BUNDLE_SYMBOLIC1 = "bundle1";

    private static final String BUNDLE_SYMBOLIC2 = "bundle2";

    private static final String BUNDLE_SYMBOLIC3 = "bundle3";

    private static final String BUNDLE_SYMBOLIC4 = "bundle4";

    private static final String BUNDLE_SYMBOLIC5 = "bundle5";

    private static final String SUB = "sub";

    private static final String NONE = "none";

    private static final String SAMPLE = "sample";

    private static final String ANOTHER = "another";

    private static final String SAMPLE_SUB = "sample_sub";

    private static final String ANOTHER_SUB = "another_sub";

    private static final Bundle BUNDLE1;

    private static final Bundle BUNDLE2;

    private static final Bundle BUNDLE3;

    private static final Bundle BUNDLE4;

    private static final Bundle BUNDLE5;

    static {
        BUNDLE1 = mock(Bundle.class);
        when(BUNDLE1.getSymbolicName()).thenReturn(BUNDLE_SYMBOLIC1);

        BUNDLE2 = mock(Bundle.class);
        when(BUNDLE2.getSymbolicName()).thenReturn(BUNDLE_SYMBOLIC2);

        BUNDLE3 = mock(Bundle.class);
        when(BUNDLE3.getSymbolicName()).thenReturn(BUNDLE_SYMBOLIC3);

        BUNDLE4 = mock(Bundle.class);
        when(BUNDLE4.getSymbolicName()).thenReturn(BUNDLE_SYMBOLIC4);

        BUNDLE5 = mock(Bundle.class);
        when(BUNDLE5.getSymbolicName()).thenReturn(BUNDLE_SYMBOLIC5);
    }

    @Test
    public void test_requiredValidators() {
        ServiceUserMapperImpl.Config config = mock(ServiceUserMapperImpl.Config.class);
        when(config.require_validation()).thenReturn(true);
        when(config.required_user_validators()).thenReturn(new String[] {"bla"});
        when(config.required_principal_validators()).thenReturn(new String[] {"bli","blub"});

        final ServiceUserMapperImpl mapper = new ServiceUserMapperImpl(null, config) {
            @Override
            void restartAllActiveServiceUserMappedServices() {
                throw new IllegalStateException();
            }
        };
        ServiceUserValidator userValidator = mock(ServiceUserValidator.class);
        ServicePrincipalsValidator principalsValidator1 = mock(ServicePrincipalsValidator.class);
        ServicePrincipalsValidator principalsValidator2 = mock(ServicePrincipalsValidator.class);

        assertTrue(mapper.isValidUser("org", "foo", "bar", false));
        assertFalse(mapper.isValidUser("org", "foo", "bar", true));

        assertTrue(mapper.areValidPrincipals(Collections.singletonList("baz"), "org", "foo", false));
        assertFalse(mapper.areValidPrincipals(Collections.singletonList("baz"), "org", "foo", true));

        Map<String, Object> properties = new HashMap<>();
        properties.put(VALIDATOR_ID, "bla");
        mapper.bindServiceUserValidator(userValidator, properties);

        assertTrue(mapper.isValidUser("org", "foo", "bar", false));
        assertFalse(mapper.isValidUser("org", "foo", "bar", true));

        assertTrue(mapper.areValidPrincipals(Collections.singletonList("baz"), "org", "foo", false));
        assertFalse(mapper.areValidPrincipals(Collections.singletonList("baz"), "org", "foo", true));

        properties.put(VALIDATOR_ID, "bli");
        mapper.bindServicePrincipalsValidator(principalsValidator2, properties);

        assertTrue(mapper.isValidUser("org", "foo", "bar", false));
        assertFalse(mapper.isValidUser("org", "foo", "bar", true));

        assertTrue(mapper.areValidPrincipals(Collections.singletonList("baz"), "org", "foo", false));
        assertFalse(mapper.areValidPrincipals(Collections.singletonList("baz"), "org", "foo", true));

        properties.put(VALIDATOR_ID, "blub");
        try {
            mapper.bindServicePrincipalsValidator(principalsValidator1, properties);
            fail();
        } catch (IllegalStateException e) {
            // Expected;
        }

        assertFalse(mapper.isValidUser("org", "foo", "bar", false));
        assertFalse(mapper.isValidUser("org", "foo", "bar", true));

        assertFalse(mapper.areValidPrincipals(Collections.singletonList("baz"), "org", "foo", false));
        assertFalse(mapper.areValidPrincipals(Collections.singletonList("baz"), "org", "foo", true));
    }

    @Test
    public void test_emptyRequiredUserAndPrincipalValidators() {
        ServiceUserMapperImpl.Config config = mock(ServiceUserMapperImpl.Config.class);
        when(config.require_validation()).thenReturn(true);
        when(config.required_user_validators()).thenReturn(new String[] {"mockRUV", ""});
        when(config.required_principal_validators()).thenReturn(new String[] {"mockRPV", ""});

        final ServiceUserMapperImpl mapper = new ServiceUserMapperImpl(null, config);

        List<String> principalNames = Collections.singletonList("bla");

        ServiceUserValidator userValidator = mock(ServiceUserValidator.class);
        when(userValidator.isValid("alice", "org", "foo")).thenReturn(true);
        Map<String, Object> properties = new HashMap<>();
        properties.put(VALIDATOR_ID, "mockRUV");
        mapper.bindServiceUserValidator(userValidator, properties);

        ServicePrincipalsValidator principalsValidator = mock(ServicePrincipalsValidator.class);
        when(principalsValidator.isValid(principalNames, "org", "foo")).thenReturn(true);
        properties.put(VALIDATOR_ID, "mockRPV");
        mapper.bindServicePrincipalsValidator(principalsValidator, properties);

        // empty validators should be skipped
        assertTrue(mapper.isValidUser("alice", "org", "foo", true));
        verify(userValidator, times(1)).isValid("alice", "org", "foo");

        assertTrue(mapper.areValidPrincipals(principalNames, "org", "foo", true));
        verify(principalsValidator, times(1)).isValid(principalNames,"org", "foo");
    }

    @Test
    public void test_getServiceUserID() {
        ServiceUserMapperImpl.Config config = mock(ServiceUserMapperImpl.Config.class);
        when(config.user_mapping()).thenReturn(new String[] {
            BUNDLE_SYMBOLIC1 + "=" + SAMPLE, //
            BUNDLE_SYMBOLIC2 + "=" + ANOTHER, //
            BUNDLE_SYMBOLIC1 + ":" + SUB + "=" + SAMPLE_SUB, //
            BUNDLE_SYMBOLIC2 + ":" + SUB + "=" + ANOTHER_SUB //
        });
        when(config.user_default()).thenReturn(NONE);
        when(config.user_enable_default_mapping()).thenReturn(false);

        final ServiceUserMapperImpl sum = new ServiceUserMapperImpl(null, config);

        assertEquals(SAMPLE, sum.getServiceUserID(BUNDLE1, null));
        assertEquals(ANOTHER, sum.getServiceUserID(BUNDLE2, null));
        assertEquals(SAMPLE, sum.getServiceUserID(BUNDLE1, ""));
        assertEquals(ANOTHER, sum.getServiceUserID(BUNDLE2, ""));
        assertEquals(SAMPLE_SUB, sum.getServiceUserID(BUNDLE1, SUB));
        assertEquals(ANOTHER_SUB, sum.getServiceUserID(BUNDLE2, SUB));
        assertEquals(NONE, sum.getServiceUserID(BUNDLE3, null));
        assertEquals(NONE, sum.getServiceUserID(BUNDLE3, SUB));
    }

    @Test
    public void test_getServiceUserIDwithDefaultMappingEnabledAndDefaultUser() {
        ServiceUserMapperImpl.Config config = mock(ServiceUserMapperImpl.Config.class);
        when(config.user_mapping()).thenReturn(new String[] {
            BUNDLE_SYMBOLIC1 + "=" + SAMPLE, //
            BUNDLE_SYMBOLIC2 + "=" + ANOTHER, //
            BUNDLE_SYMBOLIC1 + ":" + SUB + "=" + SAMPLE_SUB, //
            BUNDLE_SYMBOLIC2 + ":" + SUB + "=" + ANOTHER_SUB //
        });
        when(config.user_default()).thenReturn(NONE);
        when(config.user_enable_default_mapping()).thenReturn(true);

        final ServiceUserMapperImpl sum = new ServiceUserMapperImpl(null, config);

        assertEquals(SAMPLE, sum.getServiceUserID(BUNDLE1, null));
        assertEquals(ANOTHER, sum.getServiceUserID(BUNDLE2, null));
        assertEquals(SAMPLE, sum.getServiceUserID(BUNDLE1, ""));
        assertEquals(ANOTHER, sum.getServiceUserID(BUNDLE2, ""));
        assertEquals(SAMPLE_SUB, sum.getServiceUserID(BUNDLE1, SUB));
        assertEquals(ANOTHER_SUB, sum.getServiceUserID(BUNDLE2, SUB));
        assertEquals(NONE, sum.getServiceUserID(BUNDLE3, null));
        assertEquals(NONE, sum.getServiceUserID(BUNDLE3, SUB));
    }

    @Test
    public void test_getServiceUserIDwithDefaultMappingEnabledAndNoDefaultUser() {
        ServiceUserMapperImpl.Config config = mock(ServiceUserMapperImpl.Config.class);
        when(config.user_mapping()).thenReturn(new String[] {
            BUNDLE_SYMBOLIC1 + "=" + SAMPLE, //
            BUNDLE_SYMBOLIC2 + "=" + ANOTHER, //
            BUNDLE_SYMBOLIC1 + ":" + SUB + "=" + SAMPLE_SUB, //
            BUNDLE_SYMBOLIC2 + ":" + SUB + "=" + ANOTHER_SUB //
        });
        when(config.user_default()).thenReturn(null);
        when(config.user_enable_default_mapping()).thenReturn(true);

        final ServiceUserMapperImpl sum = new ServiceUserMapperImpl(null, config);

        assertEquals(SAMPLE, sum.getServiceUserID(BUNDLE1, null));
        assertEquals(ANOTHER, sum.getServiceUserID(BUNDLE2, null));
        assertEquals(SAMPLE, sum.getServiceUserID(BUNDLE1, ""));
        assertEquals(ANOTHER, sum.getServiceUserID(BUNDLE2, ""));
        assertEquals(SAMPLE_SUB, sum.getServiceUserID(BUNDLE1, SUB));
        assertEquals(ANOTHER_SUB, sum.getServiceUserID(BUNDLE2, SUB));
        assertEquals("serviceuser--" + BUNDLE_SYMBOLIC3, sum.getServiceUserID(BUNDLE3, null));
        assertEquals("serviceuser--" + BUNDLE_SYMBOLIC3 + "--" + SUB, sum.getServiceUserID(BUNDLE3, SUB));
    }

    @Test
    public void test_getServiceUserID_WithServiceUserValidator() {
        ServiceUserMapperImpl.Config config = mock(ServiceUserMapperImpl.Config.class);
        when(config.user_mapping()).thenReturn(new String[] {
                BUNDLE_SYMBOLIC1 + "=" + SAMPLE, //
                BUNDLE_SYMBOLIC2 + "=" + ANOTHER, //
                BUNDLE_SYMBOLIC1 + ":" + SUB + "=" + SAMPLE_SUB, //
                BUNDLE_SYMBOLIC2 + ":" + SUB + "=" + ANOTHER_SUB //
        });
        when(config.user_default()).thenReturn(NONE);
        when(config.user_enable_default_mapping()).thenReturn(false);

        final ServiceUserMapperImpl sum = new ServiceUserMapperImpl(null, config);
        ServiceUserValidator serviceUserValidator = (serviceUserId, serviceName, subServiceName) -> !SAMPLE.equals(serviceUserId);
        sum.bindServiceUserValidator(serviceUserValidator, Collections.emptyMap());

        assertNull(sum.getServiceUserID(BUNDLE1, null));
        assertEquals(ANOTHER, sum.getServiceUserID(BUNDLE2, null));
        assertNull(sum.getServiceUserID(BUNDLE1, ""));
        assertEquals(ANOTHER, sum.getServiceUserID(BUNDLE2, ""));
        assertEquals(SAMPLE_SUB, sum.getServiceUserID(BUNDLE1, SUB));
        assertEquals(ANOTHER_SUB, sum.getServiceUserID(BUNDLE2, SUB));
    }

    @Test
    public void test_getServiceUserID_WithMultipleValidators() {
        ServiceUserMapperImpl.Config config = mock(ServiceUserMapperImpl.Config.class);
        when(config.user_mapping()).thenReturn(new String[] {
                BUNDLE_SYMBOLIC1 + "=" + SAMPLE, //
                BUNDLE_SYMBOLIC2 + "=" + ANOTHER, //
                BUNDLE_SYMBOLIC1 + ":" + SUB + "=" + SAMPLE_SUB, //
                BUNDLE_SYMBOLIC2 + ":" + SUB + "=" + ANOTHER_SUB //
        });
        when(config.user_default()).thenReturn(NONE);
        when(config.user_enable_default_mapping()).thenReturn(false);

        final ServiceUserMapperImpl sum = new ServiceUserMapperImpl(null, config);
        ServiceUserValidator sampleInvalid = (serviceUserId, serviceName, subServiceName) -> !SAMPLE.equals(serviceUserId);
        sum.bindServiceUserValidator(sampleInvalid, Collections.emptyMap());

        ServiceUserValidator anotherInvalid = (serviceUserId, serviceName, subServiceName) -> !ANOTHER.equals(serviceUserId);
        sum.bindServiceUserValidator(anotherInvalid, Collections.emptyMap());

        assertNull(sum.getServiceUserID(BUNDLE1, null));
        assertNull(sum.getServiceUserID(BUNDLE2, null));
        assertNull(sum.getServiceUserID(BUNDLE1, ""));
        assertNull(sum.getServiceUserID(BUNDLE2, ""));
        assertEquals(SAMPLE_SUB, sum.getServiceUserID(BUNDLE1, SUB));
        assertEquals(ANOTHER_SUB, sum.getServiceUserID(BUNDLE2, SUB));
    }

    @Test
    public void test_getServicePrincipalNames() {
        ServiceUserMapperImpl.Config config = mock(ServiceUserMapperImpl.Config.class);
        when(config.user_mapping()).thenReturn(new String[] {
                BUNDLE_SYMBOLIC1 + "=[" + SAMPLE + "]", //
                BUNDLE_SYMBOLIC2 + "=[ " + ANOTHER + " ]", //
                BUNDLE_SYMBOLIC3 + "=[" + SAMPLE + "," + ANOTHER + "]", //
                BUNDLE_SYMBOLIC4 + "=[ " + SAMPLE + ", " + ANOTHER + " ]", //
                BUNDLE_SYMBOLIC5 + "=[]", //
                BUNDLE_SYMBOLIC1 + ":" + SUB + "=[" + SAMPLE_SUB + "]", //
                BUNDLE_SYMBOLIC2 + ":" + SUB + "=[" + SAMPLE_SUB + "," + ANOTHER_SUB + "]" //
        });

        final ServiceUserMapperImpl sum = new ServiceUserMapperImpl(null, config);

        assertEqualPrincipalNames(sum.getServicePrincipalNames(BUNDLE1, null), SAMPLE);
        assertEqualPrincipalNames(sum.getServicePrincipalNames(BUNDLE2, null), ANOTHER);
        assertEqualPrincipalNames(sum.getServicePrincipalNames(BUNDLE3, null), SAMPLE, ANOTHER);
        assertEqualPrincipalNames(sum.getServicePrincipalNames(BUNDLE4, null), SAMPLE, ANOTHER);
        assertEqualPrincipalNames(sum.getServicePrincipalNames(BUNDLE5, null));
        assertEqualPrincipalNames(sum.getServicePrincipalNames(BUNDLE1, SUB), SAMPLE_SUB);
        assertEqualPrincipalNames(sum.getServicePrincipalNames(BUNDLE2, SUB), SAMPLE_SUB, ANOTHER_SUB);
    }

    @Test
    public void test_getServicePrincipalNames_EmptySubService() {
        ServiceUserMapperImpl.Config config = mock(ServiceUserMapperImpl.Config.class);
        when(config.user_mapping()).thenReturn(new String[] {
                BUNDLE_SYMBOLIC1 + "=[" + SAMPLE + "]", //
                BUNDLE_SYMBOLIC2 + "=[ " + ANOTHER + " ]", //
        });

        final ServiceUserMapperImpl sum = new ServiceUserMapperImpl(null, config);

        assertEqualPrincipalNames(sum.getServicePrincipalNames(BUNDLE1, ""), SAMPLE);
        assertEqualPrincipalNames(sum.getServicePrincipalNames(BUNDLE2, ""), ANOTHER);
    }

    @Test
    public void test_getServicePrincipalNames_WithUserNameConfig() {
        ServiceUserMapperImpl.Config config = mock(ServiceUserMapperImpl.Config.class);
        when(config.user_mapping()).thenReturn(new String[] {
                BUNDLE_SYMBOLIC1 + "=" + SAMPLE, //
                BUNDLE_SYMBOLIC1 + ":" + SUB + "=" + SAMPLE_SUB, //
        });
        when(config.user_default()).thenReturn(NONE);
        when(config.user_enable_default_mapping()).thenReturn(false);

        final ServiceUserMapperImpl sum = new ServiceUserMapperImpl(null, config);

        assertNull(sum.getServicePrincipalNames(BUNDLE1, null));
        assertNull(SAMPLE_SUB, sum.getServicePrincipalNames(BUNDLE1, SUB));
    }

    @Test
    public void test_getServicePrincipalNames_IgnoresDefaultUser() {
        ServiceUserMapperImpl.Config config = mock(ServiceUserMapperImpl.Config.class);
        when(config.user_default()).thenReturn(NONE);
        when(config.user_enable_default_mapping()).thenReturn(true);

        final ServiceUserMapperImpl sum = new ServiceUserMapperImpl(null, config);

        assertNull(sum.getServicePrincipalNames(BUNDLE1, null));
        assertNull(sum.getServicePrincipalNames(BUNDLE1, SUB));
    }

    @Test
    public void test_getServicePrincipalnames_WithServicePrincipalsValidator() {
        ServiceUserMapperImpl.Config config = mock(ServiceUserMapperImpl.Config.class);
        when(config.user_mapping()).thenReturn(new String[] {
                BUNDLE_SYMBOLIC1 + "=[" + SAMPLE + "]", //
                BUNDLE_SYMBOLIC2 + "=[" + SAMPLE + "," + ANOTHER + "]", //
                BUNDLE_SYMBOLIC1 + ":" + SUB + "=[" + SAMPLE + "," + SAMPLE_SUB + "]", //
                BUNDLE_SYMBOLIC2 + ":" + SUB + "=[" + ANOTHER_SUB + "," + SAMPLE_SUB + "," + SAMPLE + "]"//
        });

        final ServiceUserMapperImpl sum = new ServiceUserMapperImpl(null, config);
        ServicePrincipalsValidator validator = (servicePrincipalNames, serviceName, subServiceName) -> StreamSupport.stream(servicePrincipalNames.spliterator(), false).noneMatch(SAMPLE::equals);
        sum.bindServicePrincipalsValidator(validator, Collections.emptyMap());

        assertNull(sum.getServicePrincipalNames(BUNDLE1, null));
        assertNull(sum.getServicePrincipalNames(BUNDLE2, null));
        assertNull(sum.getServicePrincipalNames(BUNDLE1, SUB));
        assertNull(sum.getServicePrincipalNames(BUNDLE2, SUB));
    }

    @Test
    public void test_getServicePrincipalnames_WithMultipleValidators() {
        ServiceUserMapperImpl.Config config = mock(ServiceUserMapperImpl.Config.class);
        when(config.user_mapping()).thenReturn(new String[] {
                BUNDLE_SYMBOLIC1 + "=[" + SAMPLE + "]", //
                BUNDLE_SYMBOLIC2 + "=[" + ANOTHER + "," + SAMPLE + "]", //
                BUNDLE_SYMBOLIC1 + ":" + SUB + "=[validPrincipal," + SAMPLE + "]", //
                BUNDLE_SYMBOLIC2 + ":" + SUB + "=[validPrincipal," + SAMPLE_SUB + "," + ANOTHER + "]"//
        });

        final ServiceUserMapperImpl sum = new ServiceUserMapperImpl(null, config);
        ServicePrincipalsValidator sampleInvalid = (servicePrincipalNames, serviceName, subServiceName) -> StreamSupport.stream(servicePrincipalNames.spliterator(), false).noneMatch(SAMPLE::equals);
        sum.bindServicePrincipalsValidator(sampleInvalid, Collections.emptyMap());

        ServicePrincipalsValidator anotherInvalid = (servicePrincipalNames, serviceName, subServiceName) -> StreamSupport.stream(servicePrincipalNames.spliterator(), false).noneMatch(ANOTHER::equals);
        sum.bindServicePrincipalsValidator(anotherInvalid, Collections.emptyMap());

        assertNull(sum.getServicePrincipalNames(BUNDLE1, null));
        assertNull(sum.getServicePrincipalNames(BUNDLE2, null));
        assertNull(sum.getServicePrincipalNames(BUNDLE1, SUB));
        assertNull(sum.getServicePrincipalNames(BUNDLE2, SUB));
    }

    @Test
    public void test_getServicePrincipalnames_WithMultipleValidators_Valid() {
        ServiceUserMapperImpl.Config config = mock(ServiceUserMapperImpl.Config.class);
        when(config.user_mapping()).thenReturn(new String[] {
                BUNDLE_SYMBOLIC1 + "=[validPrincipal]", //
                BUNDLE_SYMBOLIC1 + ":" + SUB + "=[validPrincipal," + SAMPLE_SUB + "]"//
        });

        final ServiceUserMapperImpl sum = new ServiceUserMapperImpl(null, config);
        ServicePrincipalsValidator sampleInvalid = (servicePrincipalNames, serviceName, subServiceName) -> StreamSupport.stream(servicePrincipalNames.spliterator(), false).noneMatch(SAMPLE::equals);
        sum.bindServicePrincipalsValidator(sampleInvalid, Collections.emptyMap());

        ServicePrincipalsValidator anotherInvalid = (servicePrincipalNames, serviceName, subServiceName) -> StreamSupport.stream(servicePrincipalNames.spliterator(), false).noneMatch(ANOTHER::equals);
        sum.bindServicePrincipalsValidator(anotherInvalid, Collections.emptyMap());

        assertEqualPrincipalNames(sum.getServicePrincipalNames(BUNDLE1, null), "validPrincipal");
        assertEqualPrincipalNames(sum.getServicePrincipalNames(BUNDLE1, SUB), "validPrincipal", SAMPLE_SUB);
    }

    @Test
    public void test_getServicePrincipalNamesInternal_RequiredValidators() {
        ServiceUserMapperImpl.Config config = mock(ServiceUserMapperImpl.Config.class);
        when(config.user_mapping()).thenReturn(new String[] {
                BUNDLE_SYMBOLIC1 + "=["+SAMPLE+"]", //
                BUNDLE_SYMBOLIC1 + ":" + SUB + "=["+SAMPLE+"," + SAMPLE_SUB + "]"//
        });
        when(config.required_user_validators()).thenReturn(new String[]{"requiredId_1", "requiredId_2"});
        when(config.require_validation()).thenReturn(false);

        ServiceUserMapperImpl mapper = new ServiceUserMapperImpl(null, config);

        // no required validator present
        assertEqualPrincipalNames(mapper.getServicePrincipalNamesInternal(BUNDLE1, null), SAMPLE);
        assertEqualPrincipalNames(mapper.getServicePrincipalNamesInternal(BUNDLE1, SUB), SAMPLE, SAMPLE_SUB);
    }

    @Test
    public void test_getServicePrincipalNamesInternal_RequiredValidators_RequireValidation() {
        ServiceUserMapperImpl.Config config = mock(ServiceUserMapperImpl.Config.class);
        when(config.user_mapping()).thenReturn(new String[] {
                BUNDLE_SYMBOLIC1 + "=["+SAMPLE+"]", //
                BUNDLE_SYMBOLIC1 + ":" + SUB + "=["+SAMPLE+"," + SAMPLE_SUB + "]"//
        });
        when(config.required_principal_validators()).thenReturn(new String[]{"requiredId_1", "requiredId_2"});
        when(config.require_validation()).thenReturn(true);

        ServiceUserMapperImpl mapper = new ServiceUserMapperImpl(null, config);
        ServicePrincipalsValidator validator = (servicePrincipalNames, serviceName, subServiceName) -> true;

        // no required validator present
        assertNull(mapper.getServicePrincipalNamesInternal(BUNDLE1, null));
        assertNull(mapper.getServicePrincipalNamesInternal(BUNDLE1, SUB));

        // just one required validator present
        mapper.bindServicePrincipalsValidator(validator, Collections.singletonMap(VALIDATOR_ID, "requiredId_1"));
        assertNull(mapper.getServicePrincipalNamesInternal(BUNDLE1, null));
        assertNull(mapper.getServicePrincipalNamesInternal(BUNDLE1, SUB));

        // a non-required validator present in addition
        mapper.bindServicePrincipalsValidator(validator, Collections.singletonMap(VALIDATOR_ID, "not_required"));
        assertNull(mapper.getServicePrincipalNamesInternal(BUNDLE1, null));
        assertNull(mapper.getServicePrincipalNamesInternal(BUNDLE1, SUB));

        // all required validators present
        mapper.bindServicePrincipalsValidator(validator, Collections.singletonMap(VALIDATOR_ID, "requiredId_2"));
        assertEqualPrincipalNames(mapper.getServicePrincipalNamesInternal(BUNDLE1, null), SAMPLE);
        assertEqualPrincipalNames(mapper.getServicePrincipalNamesInternal(BUNDLE1, SUB), SAMPLE, SAMPLE_SUB);
    }

    private static void assertEqualPrincipalNames(Iterable<String> result, String... expected) {
        if (expected == null) {
            assertNull(result);
        } else if (expected.length == 0) {
            assertFalse(result.iterator().hasNext());
        } else {
            Set<String> resultSet = new HashSet<>();
            for (String s : result) {
                resultSet.add(s);
            }
            Set<String> expectedSet = new HashSet<>(Arrays.asList(expected));
            assertEquals(expectedSet, resultSet);
        }
    }


    @Test
    public void test_amendment() {
        ServiceUserMapperImpl.Config config = mock(ServiceUserMapperImpl.Config.class);
        when(config.user_mapping()).thenReturn(new String[] {
                BUNDLE_SYMBOLIC1 + "=" + SAMPLE, //
                BUNDLE_SYMBOLIC1 + ":" + SUB + "=" + SAMPLE_SUB, //
        });
        when(config.user_default()).thenReturn(NONE);
        when(config.user_enable_default_mapping()).thenReturn(false);

        final ServiceUserMapperImpl sum = new ServiceUserMapperImpl(null, config);
        final MappingConfigAmendment mca1 = new MappingConfigAmendment();

        MappingConfigAmendment.Config mca1Config = mock(MappingConfigAmendment.Config.class);
        when(mca1Config.user_mapping()).thenReturn(new String[] {BUNDLE_SYMBOLIC2 + "=" + ANOTHER});
        when(mca1Config.service_ranking()).thenReturn(100);
        Map<String, Object> mca1ConfigMap = new HashMap<>();
        mca1ConfigMap.put("user.mapping", mca1Config.user_mapping());
        mca1ConfigMap.put("service.ranking", mca1Config.service_ranking());
        mca1ConfigMap.put("service.id", 1L);

        mca1.configure(mca1Config);
        sum.bindAmendment(mca1, mca1ConfigMap);
        final MappingConfigAmendment mca2 = new MappingConfigAmendment();

        MappingConfigAmendment.Config mca2Config = mock(MappingConfigAmendment.Config.class);
        when(mca2Config.user_mapping()).thenReturn(new String[] {BUNDLE_SYMBOLIC2 + ":" + SUB + "=" + ANOTHER_SUB});
        when(mca2Config.service_ranking()).thenReturn(200);
        Map<String, Object> mca2ConfigMap = new HashMap<>();
        mca2ConfigMap.put("user.mapping", mca2Config.user_mapping());
        mca2ConfigMap.put("service.ranking", mca2Config.service_ranking());
        mca2ConfigMap.put("service.id", 2L);

        mca2.configure(mca2Config);
        sum.bindAmendment(mca2, mca2ConfigMap);

        assertEquals(SAMPLE, sum.getServiceUserID(BUNDLE1, null));
        assertEquals(ANOTHER, sum.getServiceUserID(BUNDLE2, null));
        assertEquals(SAMPLE, sum.getServiceUserID(BUNDLE1, ""));
        assertEquals(ANOTHER, sum.getServiceUserID(BUNDLE2, ""));
        assertEquals(SAMPLE_SUB, sum.getServiceUserID(BUNDLE1, SUB));
        assertEquals(ANOTHER_SUB, sum.getServiceUserID(BUNDLE2, SUB));
    }

    @Test
    public void test_amendmentOverlap() {
        ServiceUserMapperImpl.Config config = mock(ServiceUserMapperImpl.Config.class);
        when(config.user_mapping()).thenReturn(new String[] {});
        when(config.user_default()).thenReturn(NONE);
        when(config.user_enable_default_mapping()).thenReturn(false);

        final ServiceUserMapperImpl sum = new ServiceUserMapperImpl(null, config);

        final MappingConfigAmendment mca1 = new MappingConfigAmendment();

        MappingConfigAmendment.Config mca1Config = mock(MappingConfigAmendment.Config.class);
        when(mca1Config.user_mapping()).thenReturn(new String[] {BUNDLE_SYMBOLIC2 + "=" + ANOTHER});
        when(mca1Config.service_ranking()).thenReturn(100);
        Map<String, Object> mca1ConfigMap = new HashMap<>();
        mca1ConfigMap.put("user.mapping", mca1Config.user_mapping());
        mca1ConfigMap.put("service.ranking", mca1Config.service_ranking());

        mca1.configure(mca1Config);
        final MappingConfigAmendment mca2 = new MappingConfigAmendment();

        MappingConfigAmendment.Config mca2Config = mock(MappingConfigAmendment.Config.class);
        when(mca2Config.user_mapping()).thenReturn(new String[] {BUNDLE_SYMBOLIC2 + "=" + ANOTHER_SUB});
        when(mca2Config.service_ranking()).thenReturn(200);
        Map<String, Object> mca2ConfigMap = new HashMap<>();
        mca2ConfigMap.put("user.mapping", mca2Config.user_mapping());
        mca2ConfigMap.put("service.ranking", mca2Config.service_ranking());

        mca2.configure(mca2Config);

        sum.bindAmendment(mca1, mca1ConfigMap);
        sum.bindAmendment(mca2, mca2ConfigMap);

        assertEquals(ANOTHER_SUB, sum.getServiceUserID(BUNDLE2, ""));
    }



    @Test
    public void test_amendmentServiceUserMapping() {

        ServiceUserMapperImpl.Config config = mock(ServiceUserMapperImpl.Config.class);
        when(config.user_mapping()).thenReturn(new String[] {
                BUNDLE_SYMBOLIC1 + "=" + SAMPLE, //
                BUNDLE_SYMBOLIC1 + ":" + SUB + "=" + SAMPLE_SUB, //
                });
        when(config.user_default()).thenReturn(NONE);
        when(config.user_enable_default_mapping()).thenReturn(false);

        ArgumentCaptor<Runnable> argument = ArgumentCaptor.forClass(Runnable.class);

        final ExecutorService executor = mock(ExecutorService.class);
        when(executor.submit(argument.capture())).thenAnswer((Answer<Future<Object>>) invocation -> {
            argument.getValue().run();
            return null;
        });

        final ServiceRegistrationContextHelper context = new ServiceRegistrationContextHelper();
        final ServiceUserMapperImpl sum = new ServiceUserMapperImpl(context.getBundleContext(), config, executor);

        assertEquals(3, context.getRegistrations(ServiceUserMappedImpl.SERVICEUSERMAPPED).size());

        final MappingConfigAmendment mca1 = new MappingConfigAmendment();

        MappingConfigAmendment.Config mca1Config = mock(MappingConfigAmendment.Config.class);
        when(mca1Config.user_mapping()).thenReturn(new String[] {BUNDLE_SYMBOLIC2 + "=" + ANOTHER});
        when(mca1Config.service_ranking()).thenReturn(100);
        Map<String, Object> mca1ConfigMap = new HashMap<>();
        mca1ConfigMap.put("user.mapping", mca1Config.user_mapping());
        mca1ConfigMap.put("service.ranking", mca1Config.service_ranking());
        mca1ConfigMap.put("service.id", 1L);

        mca1.configure(mca1Config);
        sum.bindAmendment(mca1, mca1ConfigMap);

        assertEquals(4, context.getRegistrations(ServiceUserMappedImpl.SERVICEUSERMAPPED).size());

        final MappingConfigAmendment mca2 = new MappingConfigAmendment();

        MappingConfigAmendment.Config mca2Config = mock(MappingConfigAmendment.Config.class);
        when(mca2Config.user_mapping()).thenReturn(new String[] {BUNDLE_SYMBOLIC2 + ":" + SUB + "=" + ANOTHER_SUB});
        when(mca2Config.service_ranking()).thenReturn(200);
        Map<String, Object> mca2ConfigMap = new HashMap<>();
        mca2ConfigMap.put("user.mapping", mca2Config.user_mapping());
        mca2ConfigMap.put("service.ranking", mca2Config.service_ranking());
        mca2ConfigMap.put("service.id", 2L);

        mca2.configure(mca2Config);
        sum.bindAmendment(mca2, mca2ConfigMap);

        assertEquals(5, context.getRegistrations(ServiceUserMappedImpl.SERVICEUSERMAPPED).size());

        sum.unbindAmendment(mca1, mca1ConfigMap);

        assertEquals(4, context.getRegistrations(ServiceUserMappedImpl.SERVICEUSERMAPPED).size());
    }


    private static class ServiceRegistrationContextHelper {


        final BundleContext bundleContext = mock(BundleContext.class);
        final Bundle bundle = mock(Bundle.class);
        final Map<String, List<Map.Entry<Object, Dictionary<String, Object>>>> registrations = new HashMap<>();

        public ServiceRegistrationContextHelper() {
            when(bundleContext.registerService(any(String.class), any(Object.class), any(Dictionary.class)))
                    .then((Answer<ServiceRegistration>) invocationOnMock -> {

                        Object[] arguments = invocationOnMock.getArguments();
                        return registerService((String) arguments[0], arguments[1], (Dictionary) arguments[2]);
                    });
            when(bundleContext.getBundle()).thenReturn(bundle);
            when(bundle.getSymbolicName()).thenReturn("mock");
        }

        private ServiceRegistration registerService(String string, Object o, Dictionary<String, Object> dictionary) {
            final List<Map.Entry<Object, Dictionary<String, Object>>> entries = registrations.computeIfAbsent(string, key -> new ArrayList<>());
            final Map.Entry<Object, Dictionary<String, Object>> entry = Collections.singletonMap(o, dictionary).entrySet().iterator().next();

            entries.add(entry);

            return new ServiceRegistration() {
                @Override
                public ServiceReference getReference() {
                    return null;
                }

                @Override
                public void setProperties(Dictionary dictionary) {

                }

                @Override
                public void unregister() {
                    entries.remove(entry);
                }
            };
        }

        public List<Map.Entry<Object, Dictionary<String, Object>>> getRegistrations(String name) {
            return registrations.get(name);
        }

        public BundleContext getBundleContext() {
            return bundleContext;
        }
    }
}
