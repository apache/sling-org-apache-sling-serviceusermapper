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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.apache.sling.serviceusermapping.Mapping;
import org.apache.sling.serviceusermapping.ServicePrincipalsValidator;
import org.apache.sling.serviceusermapping.ServiceUserMapped;
import org.apache.sling.serviceusermapping.ServiceUserMapper;
import org.apache.sling.serviceusermapping.ServiceUserValidator;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Designate(ocd = ServiceUserMapperImpl.Config.class)
@Component(service = {ServiceUserMapper.class, ServiceUserMapperImpl.class})
public class ServiceUserMapperImpl implements ServiceUserMapper {

    @ObjectClassDefinition(name = "Apache Sling Service User Mapper Service",
        description = "Configuration for the service mapping service names to names of users.")
    public @interface Config {

        @AttributeDefinition(name = "Service Mappings",
            description = "Provides mappings from service name to user names. "
                + "Each entry is of the form 'bundleId [ \":\" subServiceName ] \"=\" userName' | \"[\" principalNames \"]\" "
                + "where bundleId and subServiceName identify the service and userName "
                + "defines the name of the user to provide to the service; alternative the the mapping"
                + "can define a comma separated set of principalNames instead of the userName. Invalid entries are logged and ignored.")
        String[] user_mapping() default {};

        @AttributeDefinition(name = "Default User",
            description = "The name of the user to use as the default if no service mapping"
                + " applies. If this property is missing or empty no default user is defined.")
        String user_default();

        @AttributeDefinition(name = "Default Mapping",
                description = "If enabled and no mapping for a requested service user exists and no " +
                      " default user is defined, a " +
                     "default mapping is applied which uses the service user \"serviceuser--\" + bundleId + [\"--\" + subServiceName]")
        boolean user_enable_default_mapping() default true;

        @AttributeDefinition(name = "Require Validation",
                description = "If true, a service user is only valid if there are present validators that accept it.")
        boolean require_validation() default false;

        @AttributeDefinition(name = "Required User Validators",
                description = "A list of required user validators ids. If any configured validator in this list is not present " +
                        "and \"require validation\" is enabled no userid and no principal name will be valid.")
        String[] required_user_validators() default {};

        @AttributeDefinition(name = "Required Principal Validators",
                description = "A list of required principal validators ids. If any configured validator in this list is not present " +
                        "and \"require validation\" is enabled no userid and no principal name will be valid.")
        String[] required_principal_validators() default {};
    }

    /** default log */
    private final Logger log = LoggerFactory.getLogger(getClass());

    private final BundleContext bundleContext;

    private final ExecutorService executorService;

    private final List<ServiceUserValidator> userValidators = new CopyOnWriteArrayList<>();

    private final List<ServicePrincipalsValidator> principalsValidators = new CopyOnWriteArrayList<>();

    private final AtomicReference<ServiceRegistration> defaultRegistration = new AtomicReference<>();

    private final Map<Long, MappingConfigAmendment> amendments = new HashMap<>();

    private volatile Mapping[] globalServiceUserMappings;

    private volatile String defaultUser;

    private volatile boolean useDefaultMapping;

    private volatile Mapping[] activeMappings = new Mapping[0];

    private volatile SortedMap<Mapping, Registration> activeRegistrations = new TreeMap<>();

    private volatile boolean requireValidation = false;

    private final Set<String> requiredUserValidators = new HashSet<>();

    private final Set<String> requiredPrincipalValidators = new HashSet<>();

    private final List<String> presentUserValidators = new CopyOnWriteArrayList<>();

    private final List<String> presentPrincipalValidators = new CopyOnWriteArrayList<>();

    @Activate
    public ServiceUserMapperImpl(final BundleContext bundleContext, final Config config) {
        this(bundleContext, config, Executors.newSingleThreadExecutor());
    }

    public ServiceUserMapperImpl(final BundleContext bundleContext, final Config config,
            final ExecutorService executor) {
        this.bundleContext = bundleContext;
        this.executorService = executor;
        this.configure(config);
    }

    @Modified
    synchronized void configure(final Config config) {
        final String[] props = config.user_mapping();

        if ( props != null ) {
            final ArrayList<Mapping> mappings = new ArrayList<>(props.length);
            for (final String prop : props) {
                if (prop != null && prop.trim().length() > 0 ) {
                    try {
                        final Mapping mapping = new Mapping(prop.trim());
                        mappings.add(mapping);
                    } catch (final IllegalArgumentException iae) {
                        log.error("configure: Ignoring '{}': {}", prop, iae.getMessage());
                    }
                }
            }

            this.globalServiceUserMappings = mappings.toArray(new Mapping[mappings.size()]);
        } else {
            this.globalServiceUserMappings = new Mapping[0];
        }
        this.defaultUser = config.user_default();
        if (this.defaultUser != null && this.defaultUser.isEmpty()) {
            this.defaultUser = null;
        }
        this.useDefaultMapping = config.user_enable_default_mapping();
        this.requireValidation = config.require_validation();

        if (config.required_user_validators() != null) {
            requiredUserValidators.addAll(filterEmptyStringValues(config.required_user_validators()));
        }

        if (config.required_principal_validators() != null) {
            requiredPrincipalValidators.addAll(filterEmptyStringValues(config.required_principal_validators()));
        }

        RegistrationSet registrationSet = this.updateMappings();

        this.executeServiceRegistrationsAsync(registrationSet);
    }

    @Deactivate
    synchronized void deactivate() {
        // this call does not unregister the mappings, but they should be unbound
        // through the unbind methods anyway
        updateServiceRegistrations(new Mapping[0]);
        executorService.shutdown();
    }

    void restartAllActiveServiceUserMappedServices() {
        RegistrationSet registrationSet = new RegistrationSet();
        registrationSet.removed = activeRegistrations.values();
        registrationSet.added = activeRegistrations.values();
        executeServiceRegistrationsAsync(registrationSet);
    }

    /**
     * bind the serviceUserValidator
     * @param serviceUserValidator
     */
    @Reference(cardinality=ReferenceCardinality.MULTIPLE, policy= ReferencePolicy.DYNAMIC)
    protected synchronized void bindServiceUserValidator(final ServiceUserValidator serviceUserValidator, final Map<String, ?> props) {
        userValidators.add(serviceUserValidator);
        Object id = props.get(VALIDATOR_ID);
        if (id instanceof String) {
            presentUserValidators.add((String) id);
        }

        if (!requireValidation || !getPrincipalsValidators().isEmpty()) {
            restartAllActiveServiceUserMappedServices();
        }
    }

    /**
     * unbind the serviceUserValidator
     * @param serviceUserValidator
     */
    protected synchronized void unbindServiceUserValidator(final ServiceUserValidator serviceUserValidator, final Map<String, ?> props) {
        userValidators.remove(serviceUserValidator);
        Object id = props.get(VALIDATOR_ID);
        if (id instanceof String) {
            presentUserValidators.remove(id);
        }

        restartAllActiveServiceUserMappedServices();
    }

    /**
     * bind the servicePrincipalsValidator
     * @param servicePrincipalsValidator
     */
    @Reference(cardinality=ReferenceCardinality.MULTIPLE, policy= ReferencePolicy.DYNAMIC)
    protected synchronized void bindServicePrincipalsValidator(final ServicePrincipalsValidator servicePrincipalsValidator, final Map<String, ?> props) {
        principalsValidators.add(servicePrincipalsValidator);
        Object id = props.get(VALIDATOR_ID);
        if (id instanceof String) {
            presentPrincipalValidators.add((String) id);
        }

        if (!requireValidation || !getUserValidators().isEmpty()) {
            restartAllActiveServiceUserMappedServices();
        }
    }

    /**
     * unbind the servicePrincipalsValidator
     * @param servicePrincipalsValidator
     */
    protected synchronized void unbindServicePrincipalsValidator(final ServicePrincipalsValidator servicePrincipalsValidator, final Map<String, ?> props) {
        principalsValidators.remove(servicePrincipalsValidator);
        Object id = props.get(VALIDATOR_ID);
        if (id instanceof String) {
            presentPrincipalValidators.remove(id);
        }
        restartAllActiveServiceUserMappedServices();
    }

    /**
     * @see org.apache.sling.serviceusermapping.ServiceUserMapper#getServiceUserID(org.osgi.framework.Bundle, java.lang.String)
     */
    @Override
    public String getServiceUserID(final Bundle bundle, final String subServiceName) {
        final String serviceName = getServiceName(bundle);
        final String userId = internalGetUserId(serviceName, subServiceName);
        final boolean valid = isValidUser(userId, serviceName, subServiceName, false);
        final String result = valid ? userId : null;
        log.debug(
                "getServiceUserID(bundle {}, subServiceName {}) returns [{}] (raw userId={}, valid={})",
                new Object[] { bundle, subServiceName, result, userId, valid });
        return result;
    }

    String getServiceUserIDInternal(final Bundle bundle, final String subServiceName) {
        final String serviceName = getServiceName(bundle);
        final String userId = internalGetUserId(serviceName, subServiceName);
        final boolean valid = isValidUser(userId, serviceName, subServiceName, requireValidation);
        final String result = valid ? userId : null;
        log.debug(
                "getServiceUserID(bundle {}, subServiceName {}) returns [{}] (raw userId={}, valid={})",
                new Object[] { bundle, subServiceName, result, userId, valid });
        return result;
    }

    /**
     * @see org.apache.sling.serviceusermapping.ServiceUserMapper#getServicePrincipalNames(org.osgi.framework.Bundle, java.lang.String)
     */
    @Override
    public Iterable<String> getServicePrincipalNames(Bundle bundle, String subServiceName) {
        final String serviceName = getServiceName(bundle);
        final Iterable<String> names = internalGetPrincipalNames(serviceName, subServiceName);
        final boolean valid = areValidPrincipals(names, serviceName, subServiceName, false);
        final Iterable<String> result = valid ? names : null;
        log.debug(
                "getServicePrincipalNames(bundle {}, subServiceName {}) returns [{}] (raw principalNames={}, valid={})",
                new Object[] { bundle, subServiceName, result, names, valid});
        return result;
    }

    Iterable<String> getServicePrincipalNamesInternal(Bundle bundle, String subServiceName) {
        final String serviceName = getServiceName(bundle);
        final Iterable<String> names = internalGetPrincipalNames(serviceName, subServiceName);
        final boolean valid = areValidPrincipals(names, serviceName, subServiceName, requireValidation);
        final Iterable<String> result = valid ? names : null;
        log.debug(
                "getServicePrincipalNames(bundle {}, subServiceName {}) returns [{}] (raw principalNames={}, valid={})",
                new Object[] { bundle, subServiceName, result, names, valid});
        return result;
    }

    @Reference(cardinality=ReferenceCardinality.MULTIPLE,policy=ReferencePolicy.DYNAMIC,updated="updateAmendment")
    protected synchronized void bindAmendment(final MappingConfigAmendment amendment, final Map<String, Object> props) {
        final Long key = (Long) props.get(Constants.SERVICE_ID);
        RegistrationSet registrationSet = null;
        amendments.put(key, amendment);
        registrationSet = this.updateMappings();
        executeServiceRegistrationsAsync(registrationSet);
    }

    protected synchronized void unbindAmendment(final MappingConfigAmendment amendment, final Map<String, Object> props) {
        final Long key = (Long) props.get(Constants.SERVICE_ID);
        RegistrationSet registrationSet = null;
        if ( amendments.remove(key) != null ) {
             registrationSet = this.updateMappings();
        }
        executeServiceRegistrationsAsync(registrationSet);
    }

    protected void updateAmendment(final MappingConfigAmendment amendment, final Map<String, Object> props) {
        this.bindAmendment(amendment, props);
    }

    protected RegistrationSet updateMappings() {
        final List<MappingConfigAmendment> sortedMappings = new ArrayList<>();
        for(final MappingConfigAmendment amendment : this.amendments.values() ) {
            sortedMappings.add(amendment);
        }
        Collections.sort(sortedMappings);

        final List<Mapping> mappings = new ArrayList<>();
        for(final Mapping m : this.globalServiceUserMappings) {
            mappings.add(m);
        }
        for(final MappingConfigAmendment mca : sortedMappings) {
            for(final Mapping m : mca.getServiceUserMappings()) {
                mappings.add(m);
            }
        }

        activeMappings = mappings.toArray(new Mapping[mappings.size()]);
        log.debug("Active mappings updated: {} mappings active", mappings.size());

        return updateServiceRegistrations(activeMappings);

    }


    RegistrationSet updateServiceRegistrations(final Mapping[] newMappings) {

        RegistrationSet result = new RegistrationSet();

        final SortedSet<Mapping> orderedNewMappings = new TreeSet<>(Arrays.asList(newMappings));
        final SortedMap<Mapping, Registration> newRegistrations = new TreeMap<>();

        // keep those that are still mapped
        for (Map.Entry<Mapping, Registration> registrationEntry: activeRegistrations.entrySet()) {
            boolean keepEntry = true;

            if (!orderedNewMappings.contains(registrationEntry.getKey())) {
                Registration registration = registrationEntry.getValue();

                result.removed.add(registration);
                keepEntry = false;
            }

            if (keepEntry) {
                newRegistrations.put(registrationEntry.getKey(), registrationEntry.getValue());
            }
        }

        // add those that are new
        for (final Mapping mapping: orderedNewMappings) {
            if (!newRegistrations.containsKey(mapping)) {
                Registration registration = new Registration(mapping);
                newRegistrations.put(mapping, registration);
                result.added.add(registration);
            }
        }

        activeRegistrations = newRegistrations;

        return result;
    }

    private void executeServiceRegistrationsAsync(final RegistrationSet registrationSet) {
        executorService.submit(() -> executeServiceRegistrations(registrationSet));
    }


    private void executeServiceRegistrations(final RegistrationSet registrationSet) {

        final ServiceRegistration reg = defaultRegistration.getAndSet(null);
        if (reg != null) {
            reg.unregister();
        }

        for (final Registration registration : registrationSet.removed) {

            ServiceRegistration serviceRegistration = registration.setService(null);

            if (serviceRegistration != null) {
                try {
                    serviceRegistration.unregister();
                    log.debug("Unregistered ServiceUserMapped {}", registration.mapping);
                } catch (final IllegalStateException e) {
                    // this can happen on shutdown, therefore we just ignore it and don't log
                }
            }
        }

        for (final Registration registration : registrationSet.added) {
            Mapping mapping = registration.mapping;
            final Dictionary<String, Object> properties = new Hashtable<>();
            if (mapping.getSubServiceName() != null) {
                properties.put(ServiceUserMapped.SUBSERVICENAME, mapping.getSubServiceName());
            }

            properties.put(Mapping.SERVICENAME, mapping.getServiceName());
            final ServiceRegistration serviceRegistration = bundleContext.registerService(
                    ServiceUserMappedImpl.SERVICEUSERMAPPED,
                    ServiceUserMappedImpl.SINGLETON, properties);

            ServiceRegistration oldServiceRegistration = registration.setService(serviceRegistration);
            log.debug("Activated ServiceUserMapped {}", registration.mapping);

            if (oldServiceRegistration != null) {
                try {
                    oldServiceRegistration.unregister();
                } catch (final IllegalStateException e) {
                    // this can happen on shutdown, therefore we just ignore it and don't log
                }
            }
        }

        if (this.useDefaultMapping || defaultUser != null) {
            Dictionary<String, Object> properties = new Hashtable<>();
            properties.put(Mapping.SERVICENAME, getServiceName(bundleContext.getBundle()));
            final ServiceRegistration serviceRegistration = bundleContext
                    .registerService(ServiceUserMappedImpl.SERVICEUSERMAPPED,
                ServiceUserMappedImpl.SINGLETON, properties);
            this.defaultRegistration.set(serviceRegistration);
        }
    }

    private String internalGetUserId(final String serviceName, final String subServiceName) {
        log.debug(
                "internalGetUserId: {} active mappings, looking for mapping for {}/{}",
                new Object[] { this.activeMappings.length, serviceName, subServiceName });

        for (final Mapping mapping : this.activeMappings) {
            final String userId = mapping.map(serviceName, subServiceName);
            if (userId != null) {
                log.debug("Got userId [{}] from {}/{}", new Object[] { userId, serviceName, subServiceName });
                return userId;
            }
        }

        // second round without serviceInfo
        log.debug(
                "internalGetUserId: {} active mappings, looking for mapping for {}/<no subServiceName>",
                this.activeMappings.length, serviceName);

        for (Mapping mapping : this.activeMappings) {
            final String userId = mapping.map(serviceName, null);
            if (userId != null) {
                log.debug("Got userId [{}] from {}/<no subServiceName>", userId, serviceName);
                return userId;
            }
        }

        // use default mapping if configured and no default user
        if (this.useDefaultMapping && this.defaultUser == null) {
            final String userName = "serviceuser--" + serviceName + (subServiceName == null ? "" : "--" + subServiceName);
            log.debug("internalGetUserId: no mapping found, using default mapping [{}]", userName);
            return userName;

        }
        log.debug("internalGetUserId: no mapping found, fallback to default user [{}]", this.defaultUser);
        return this.defaultUser;
    }

    boolean isValidUser(final String userId, final String serviceName, final String subServiceName, boolean require) {
        if (userId == null) {
            log.debug("isValidUser: userId is null -> invalid");
            return false;
        }
        List<ServiceUserValidator> validators = getUserValidators();
        if (validators.isEmpty()) {
            if (require) {
                log.debug("isValidUser: No active validators for userId '{}' and require -> invalid", userId);
                return false;
            } else {
                log.debug("isValidUser: No active validators for userId '{}' -> valid", userId);
                return true;
            }
        } else {
            for (final ServiceUserValidator validator : validators) {
                if (!validator.isValid(userId, serviceName, subServiceName)) {
                    log.debug("isValidUser: Validator {} doesn't accept userId '{}' -> invalid", validator, userId);
                    return false;
                }
            }
            log.debug("isValidUser: All validators accepted userId '{}' -> valid", userId);
            return true;
        }
    }

    boolean areValidPrincipals(final Iterable<String> principalNames, final String serviceName, final String subServiceName, boolean require) {
        if (principalNames == null) {
            log.debug("areValidPrincipals: principalNames are null -> invalid");
            return false;
        }
        List<ServicePrincipalsValidator> validators = getPrincipalsValidators();
        if (validators.isEmpty()) {
            if (require) {
                log.debug("areValidPrincipals: No active validators for principal names [{}] and require -> invalid", principalNames);
                return false;
            } else {
                log.debug("areValidPrincipals: No active validators for principal names [{}] -> valid", principalNames);
                return true;
            }
        } else {
            for (final ServicePrincipalsValidator validator : validators) {
                if (!validator.isValid(principalNames, serviceName, subServiceName)) {
                    log.debug("areValidPrincipals: Validator {} doesn't accept principal names [{}] -> invalid", validator, principalNames);
                    return false;
                }
            }
            log.debug("areValidPrincipals: All validators accepted principal names [{}] -> valid", principalNames);
            return true;
        }
    }

    private List<ServiceUserValidator> getUserValidators() {
        return getValidatorsIfPresent(userValidators);
    }

    private List<ServicePrincipalsValidator> getPrincipalsValidators() {
        return getValidatorsIfPresent(principalsValidators);
    }

    private List<String> filterEmptyStringValues(String[] arr) {
        return Arrays.stream(arr).filter(p -> !p.trim().isEmpty()).collect(Collectors.toList());
    }

    private <T> List<T> getValidatorsIfPresent(List<T> validators) {
        if (presentUserValidators.containsAll(requiredUserValidators) && presentPrincipalValidators.containsAll(requiredPrincipalValidators)) {
            return validators;
        } else {
            return Collections.emptyList();
        }
    }

    private Iterable<String> internalGetPrincipalNames(final String serviceName, final String subServiceName) {
        log.debug(
                "internalGetPrincipalNames: {} active mappings, looking for mapping for {}/{}",
                new Object[] { this.activeMappings.length, serviceName, subServiceName });

        for (final Mapping mapping : this.activeMappings) {
            final Iterable<String> principalNames = mapping.mapPrincipals(serviceName, subServiceName);
            if (principalNames != null) {
                log.debug("Got principalNames [{}] from {}/{}", new Object[] {principalNames, serviceName, subServiceName });
                return principalNames;
            }
        }

        // second round without serviceInfo
        log.debug(
                "internalGetPrincipalNames: {} active mappings, looking for mapping for {}/<no subServiceName>",
                this.activeMappings.length, serviceName);

        for (Mapping mapping : this.activeMappings) {
            final Iterable<String> principalNames = mapping.mapPrincipals(serviceName, null);
            if (principalNames != null) {
                log.debug("Got principalNames [{}] from {}/<no subServiceName>", principalNames, serviceName);
                return principalNames;
            }
        }

        log.debug("internalGetPrincipalNames: no mapping found.");
        return null;
    }

    String getServiceName(final Bundle bundle) {
        return bundle.getSymbolicName();
    }

    @Override
    public List<Mapping> getActiveMappings() {
        return Collections.unmodifiableList(Arrays.asList(activeMappings));
    }

    class Registration {
        private Mapping mapping;
        private ServiceRegistration serviceRegistration;


        Registration(Mapping mapping) {
            this.mapping = mapping;
            this.serviceRegistration = null;
        }

        synchronized ServiceRegistration setService(ServiceRegistration serviceRegistration) {
            ServiceRegistration oldServiceRegistration = this.serviceRegistration;
            this.serviceRegistration = serviceRegistration;
            return oldServiceRegistration;
        }
    }

    class RegistrationSet {
        Collection<Registration> added = new ArrayList<>();
        Collection<Registration> removed = new ArrayList<>();
    }
}

