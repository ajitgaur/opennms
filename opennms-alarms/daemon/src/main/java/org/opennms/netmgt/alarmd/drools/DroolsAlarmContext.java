/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2018 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2018 The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * OpenNMS(R) is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with OpenNMS(R).  If not, see:
 *      http://www.gnu.org/licenses/
 *
 * For more information contact:
 *     OpenNMS(R) Licensing <license@opennms.org>
 *     http://www.opennms.org/
 *     http://www.opennms.com/
 *******************************************************************************/

package org.opennms.netmgt.alarmd.drools;

import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.hibernate.Hibernate;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.rule.FactHandle;
import org.opennms.core.criteria.CriteriaBuilder;
import org.opennms.core.utils.ConfigFileConstants;
import org.opennms.netmgt.alarmd.Alarmd;
import org.opennms.netmgt.alarmd.api.AlarmLifecycleListener;
import org.opennms.netmgt.dao.api.AcknowledgmentDao;
import org.opennms.netmgt.model.AckAction;
import org.opennms.netmgt.model.AlarmAssociation;
import org.opennms.netmgt.model.OnmsAcknowledgment;
import org.opennms.netmgt.model.OnmsAlarm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.google.common.collect.Sets;

/**
 * This class maintains the Drools context used to manage the lifecycle of the alarms.
 *
 * We drive the facts in the Drools context using callbacks provided by the {@link AlarmLifecycleListener}.
 *
 * We use a lock updating alarms in the context in order to avoid triggering the rules while an incomplete
 * view of the alarms is present in the working memory.
 *
 * @author jwhite
 */
public class DroolsAlarmContext extends ManagedDroolsContext implements AlarmLifecycleListener {
    private static final Logger LOG = LoggerFactory.getLogger(DroolsAlarmContext.class);

    @Autowired
    private AlarmService alarmService;

    @Autowired
    private AcknowledgmentDao acknowledgmentDao;

    @Autowired
    private AlarmTicketerService alarmTicketerService;

    private final Map<Integer, AlarmAndFact> alarmsById = new HashMap<>();

    private final Map<Integer, AlarmAcknowledgementAndFact> acknowledgementsByAlarmId = new HashMap<>();

    private final Map<Integer, Map<Integer, AlarmAssociationAndFact>> alarmAssociationById = new HashMap<>();

    public DroolsAlarmContext() {
        super(Paths.get(ConfigFileConstants.getHome(), "etc", "alarmd", "drools-rules.d").toFile(), Alarmd.NAME, "DroolsAlarmContext");
        setOnNewKiewSessionCallback(kieSession -> {
            kieSession.setGlobal("alarmService", alarmService);
            kieSession.insert(alarmTicketerService);

            // Rebuild the alarm id -> fact handle map
            alarmsById.clear();
            for (FactHandle fact : kieSession.getFactHandles()) {
                final Object objForFact = kieSession.getObject(fact);
                if (objForFact instanceof OnmsAlarm) {
                    final OnmsAlarm alarmInSession = (OnmsAlarm)objForFact;
                    alarmsById.put(alarmInSession.getId(), new AlarmAndFact(alarmInSession, fact));
                }
            }
        });
    }

    @Override
    public void handleAlarmSnapshot(List<OnmsAlarm> alarms) {
        if (!isStarted()) {
            LOG.debug("Ignoring alarm snapshot. Drools session is stopped.");
            return;
        }
        lockIfNotFiring();
        try {
            LOG.debug("Handling snapshot for {} alarms.", alarms.size());
            final Map<Integer, OnmsAlarm> alarmsInDbById = alarms.stream()
                    .filter(a -> a.getId() != null)
                    .collect(Collectors.toMap(OnmsAlarm::getId, a -> a));

            final Set<Integer> alarmIdsInDb = alarmsInDbById.keySet();
            final Set<Integer> alarmIdsInWorkingMem = alarmsById.keySet();

            final Set<Integer> alarmIdsToAdd = Sets.difference(alarmIdsInDb, alarmIdsInWorkingMem).immutableCopy();
            final Set<Integer> alarmIdsToRemove = Sets.difference(alarmIdsInWorkingMem, alarmIdsInDb).immutableCopy();
            final Set<Integer> alarmIdsToUpdate = Sets.intersection(alarmIdsInWorkingMem, alarmIdsInDb)
                    .stream()
                    .filter(alarmId -> {
                        final AlarmAndFact alarmAndFact = alarmsById.get(alarmId);
                        final OnmsAlarm alarmInMem = alarmAndFact.getAlarm();
                        final OnmsAlarm alarmInDb = alarmsInDbById.get(alarmId);
                        // Only update the alarms if they are different
                        return shouldUpdateAlarmForSnapshot(alarmInMem, alarmInDb);
                    })
                    .collect(Collectors.toSet());

            // Log details that help explain what actions are being performed, if any
            if (LOG.isDebugEnabled()) {
                if (!alarmIdsToAdd.isEmpty() || !alarmIdsToRemove.isEmpty() || !alarmIdsToUpdate.isEmpty()) {
                    LOG.debug("Adding {} alarms, removing {} alarms and updating {} alarms for snapshot.",
                            alarmIdsToAdd.size(), alarmIdsToRemove.size(), alarmIdsToUpdate.size());
                } else {
                    LOG.debug("No actions to perform for alarm snapshot.");
                }
                // When TRACE is enabled, include diagnostic information to help explain why
                // the alarms are being updated
                if (LOG.isTraceEnabled()) {
                    for (Integer alarmIdToUpdate : alarmIdsToUpdate) {
                        LOG.trace("Updating alarm with id={}. Alarm from DB: {} vs Alarm from memory: {}",
                                alarmIdToUpdate,
                                alarmsInDbById.get(alarmIdToUpdate),
                                alarmsById.get(alarmIdToUpdate));
                    }
                }
            }

            for (Integer alarmIdToRemove : alarmIdsToRemove) {
                handleDeletedAlarmNoLock(alarmIdToRemove);
            }
            for (Integer alarmIdToAdd : alarmIdsToAdd) {
                handleNewOrUpdatedAlarmNoLock(alarmsInDbById.get(alarmIdToAdd));
            }
            for (Integer alarmIdToUpdate : alarmIdsToUpdate) {
                handleNewOrUpdatedAlarmNoLock(alarmsInDbById.get(alarmIdToUpdate));
            }

            LOG.debug("Done handling snapshot.");
        } finally {
            unlockIfNotFiring();
        }
    }

    /**
     * Used to determine if an alarm that is presently in the working memory should be updated
     * with the given alarm, when handling alarm snapshots.
     *
     * @param alarmInMem the alarm that is currently in the working memory
     * @param alarmInDb the alarm that is currently in the database
     * @return true if the alarm in the working memory should be updated, false otherwise
     */
    protected static boolean shouldUpdateAlarmForSnapshot(OnmsAlarm alarmInMem, OnmsAlarm alarmInDb) {
        return !Objects.equals(alarmInMem.getLastEventTime(), alarmInDb.getLastEventTime()) ||
                !Objects.equals(alarmInMem.getAckTime(), alarmInDb.getAckTime());
    }

    @Override
    public void handleNewOrUpdatedAlarm(OnmsAlarm alarm) {
        if (!isStarted()) {
            LOG.debug("Ignoring new/updated alarm. Drools session is stopped.");
            return;
        }
        lockIfNotFiring();
        try {
            handleNewOrUpdatedAlarmNoLock(alarm);
        } finally {
            unlockIfNotFiring();
        }
    }

    private void handleNewOrUpdatedAlarmNoLock(OnmsAlarm alarm) {
        final KieSession kieSession = getKieSession();
        // Initialize any related objects that are needed for rule execution
        Hibernate.initialize(alarm.getAssociatedAlarms());
        final AlarmAndFact alarmAndFact = alarmsById.get(alarm.getId());
        if (alarmAndFact == null) {
            LOG.debug("Inserting alarm into session: {}", alarm);
            final FactHandle fact = getKieSession().insert(alarm);
            alarmsById.put(alarm.getId(), new AlarmAndFact(alarm, fact));
        } else {
            // Updating the fact doesn't always give us to expected results so we resort to deleting it
            // and adding it again instead
            LOG.trace("Deleting alarm from session (for re-insertion): {}", alarm);
            kieSession.delete(alarmAndFact.getFact());
            // Reinsert
            LOG.trace("Re-inserting alarm into session: {}", alarm);
            final FactHandle fact = kieSession.insert(alarm);
            alarmsById.put(alarm.getId(), new AlarmAndFact(alarm, fact));
        }
        handleRelatedAlarms(alarm);
        handleAlarmAcknowledgements(alarm);
    }

    @Override
    public void handleDeletedAlarm(int alarmId, String reductionKey) {
        if (!isStarted()) {
            LOG.debug("Ignoring deleted alarm. Drools session is stopped.");
            return;
        }
        lockIfNotFiring();
        try {
            handleDeletedAlarmNoLock(alarmId);
        } finally {
            unlockIfNotFiring();
        }
    }

    private void handleRelatedAlarms(OnmsAlarm situation) {
        if (!situation.isSituation()) {
            return;
        }
        alarmAssociationById.putIfAbsent(situation.getId(), new HashMap<>());
        final Map<Integer, AlarmAssociationAndFact> associationFacts = alarmAssociationById.get(situation.getId());
        for (AlarmAssociation association : situation.getAssociatedAlarms()) {
            Integer alarmId = association.getRelatedAlarm().getId();
            AlarmAssociationAndFact assocationFact = associationFacts.get(alarmId);
            if (assocationFact == null) {
                LOG.debug("Inserting alarm association into session: {}", association);
                final FactHandle fact = getKieSession().insert(association);
                associationFacts.put(alarmId, new AlarmAssociationAndFact(association, fact));
            } else {
                FactHandle fact = assocationFact.getFact();
                LOG.trace("Updating alarm assocation in session: {}", assocationFact);
                getKieSession().update(fact, association);
                associationFacts.put(alarmId, new AlarmAssociationAndFact(association, fact)); 
            }
        }
        // Remove Fact for any Alarms no longer in the Situation
        Set<Integer> deletedAlarmIds = associationFacts.values().stream()
                .map(fact -> fact.getAlarmAssociation().getRelatedAlarm().getId())
                    .filter(alarmId -> !situation.getRelatedAlarmIds().contains(alarmId))
                    .collect(Collectors.toSet());
        deletedAlarmIds.forEach(alarmId -> {
            final AlarmAssociationAndFact associationAndFact = associationFacts.remove(alarmId);
            if (associationAndFact != null) {
                LOG.debug("Deleting AlarmAssociationAndFact from session: {}", associationAndFact.getAlarmAssociation());
                getKieSession().delete(associationAndFact.getFact());
            }
        });
    }

    private void handleAlarmAcknowledgements(OnmsAlarm alarm) {
        final AlarmAcknowledgementAndFact acknowledgmentFact = acknowledgementsByAlarmId.get(alarm.getId());
        if (acknowledgmentFact == null) {
            OnmsAcknowledgment ack = getLatestAcknowledgement(alarm);
            LOG.debug("Inserting first alarm acknowledgement into session: {}", ack);
            final FactHandle fact = getKieSession().insert(ack);
            acknowledgementsByAlarmId.put(alarm.getId(), new AlarmAcknowledgementAndFact(ack, fact));
        } else {
            FactHandle fact = acknowledgmentFact.getFact();
            OnmsAcknowledgment ack = getLatestAcknowledgement(alarm);
            LOG.trace("Updating acknowledgment in session: {}", ack);
            getKieSession().update(fact, ack);
            acknowledgementsByAlarmId.put(alarm.getId(), new AlarmAcknowledgementAndFact(ack, fact));
        }
    }

    private OnmsAcknowledgment getLatestAcknowledgement(OnmsAlarm alarm) {
        final CriteriaBuilder builder = new CriteriaBuilder(OnmsAcknowledgment.class)
                .eq("refId", alarm.getId())
                .limit(1)
                .orderBy("ackTime").desc()
                .orderBy("id").desc();
        List<OnmsAcknowledgment> acks = acknowledgmentDao.findMatching(builder.toCriteria());
        if (acks.isEmpty()) {
            // For the purpose of making rule writing easier, we fake an
            // Un-Acknowledgment for Alarms that have never been Acknowledged.
            OnmsAcknowledgment ack = new OnmsAcknowledgment(alarm, DefaultAlarmService.DEFAULT_USER, alarm.getFirstEventTime());
            ack.setAckAction(AckAction.UNACKNOWLEDGE);
            ack.setId(0);
            return ack;
        } else {
            return acks.get(0);
        }
    }

    private void handleDeletedAlarmNoLock(int alarmId) {
        final AlarmAndFact alarmAndFact = alarmsById.remove(alarmId);
        if (alarmAndFact != null) {
            LOG.debug("Deleting alarm from session: {}", alarmAndFact.getAlarm());
            getKieSession().delete(alarmAndFact.getFact());
        }
        deleteAlarmAcknowledgement(alarmId);
        deleteAlarmAssociations(alarmId);
    }

    private void deleteAlarmAcknowledgement(int alarmId) {
        final AlarmAcknowledgementAndFact acknowledgmentFact = acknowledgementsByAlarmId.remove(alarmId);
        if (acknowledgmentFact != null) {
            LOG.debug("Deleting ack from session: {}", acknowledgmentFact.getAcknowledgement());
            getKieSession().delete(acknowledgmentFact.getFact());
        }
    }

    private void deleteAlarmAssociations(int alarmId) {
        Map<Integer, AlarmAssociationAndFact> associationFacts = alarmAssociationById.remove(alarmId);
        if (associationFacts == null) {
            return;
        }
        for (Integer association : associationFacts.keySet()) {
            AlarmAssociationAndFact assocationFact = associationFacts.get(association);
            if (assocationFact != null) {
                LOG.debug("Deleting association from session: {}", assocationFact.getAlarmAssociation());
                getKieSession().delete(assocationFact.getFact());
            }
        }
    }

    public void setAlarmService(AlarmService alarmService) {
        this.alarmService = alarmService;
    }

    public void setAcknowledgmentDao(AcknowledgmentDao acknowledgmentDao) {
        this.acknowledgmentDao = acknowledgmentDao;
    }

    public void setAlarmTicketerService(AlarmTicketerService alarmTicketerService) {
        this.alarmTicketerService = alarmTicketerService;
    }

}
