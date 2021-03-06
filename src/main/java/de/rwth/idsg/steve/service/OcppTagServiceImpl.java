package de.rwth.idsg.steve.service;

import de.rwth.idsg.steve.SteveException;
import de.rwth.idsg.steve.repository.OcppTagRepository;
import de.rwth.idsg.steve.repository.SettingsRepository;
import de.rwth.idsg.steve.repository.TransactionRepository;
import de.rwth.idsg.steve.service.dto.UnidentifiedIncomingObject;
import jooq.steve.db.tables.records.OcppTagActivityRecord;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ocpp.cp._2015._10.AuthorizationData;
import ocpp.cs._2015._10.AuthorizationStatus;
import ocpp.cs._2015._10.IdTagInfo;
import org.jetbrains.annotations.Nullable;
import org.joda.time.DateTime;
import org.jooq.RecordMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * @author Sevket Goekay <goekay@dbis.rwth-aachen.de>
 * @since 03.01.2015
 */
@Slf4j
@Service
public class OcppTagServiceImpl implements OcppTagService {

    @Autowired private SettingsRepository settingsRepository;
    @Autowired private OcppTagRepository ocppTagRepository;
    @Autowired private TransactionRepository transactionRepository;

    private final UnidentifiedIncomingObjectService invalidOcppTagService = new UnidentifiedIncomingObjectService(1000);

    @Override
    public List<AuthorizationData> getAuthDataOfAllTags() {
        return ocppTagRepository.getRecords()
                                .map(new AuthorisationDataMapper());
    }

    @Override
    public List<AuthorizationData> getAuthData(List<String> idTagList) {
        return ocppTagRepository.getRecords(idTagList)
                                .map(new AuthorisationDataMapper());
    }

    @Override
    public List<UnidentifiedIncomingObject> getUnknownOcppTags() {
        return invalidOcppTagService.getObjects();
    }

    @Override
    public void removeUnknown(String idTag) {
        invalidOcppTagService.remove(idTag);
    }

    @Override
    public void removeUnknown(List<String> idTagList) {
        invalidOcppTagService.removeAll(idTagList);
    }

    @Override
    public IdTagInfo getIdTagInfo(String idTag, String askingChargeBoxId) {
        OcppTagActivityRecord record = ocppTagRepository.getRecord(idTag);
        AuthorizationStatus status = decideStatus(record, idTag, askingChargeBoxId);

        switch (status) {
            case INVALID:
                invalidOcppTagService.processNewUnidentified(idTag);
                return new IdTagInfo().withStatus(status);

            case BLOCKED:
            case EXPIRED:
            case CONCURRENT_TX:
            case ACCEPTED:
                return new IdTagInfo().withStatus(status)
                                      .withParentIdTag(record.getParentIdTag())
                                      .withExpiryDate(getExpiryDateOrDefault(record));
            default:
                throw new SteveException("Unexpected AuthorizationStatus");
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * If the database contains an actual expiry, use it. Otherwise, calculate an expiry for cached info
     */
    @Nullable
    private DateTime getExpiryDateOrDefault(OcppTagActivityRecord record) {
        if (record.getExpiryDate() != null) {
            return record.getExpiryDate();
        }

        int hoursToExpire = settingsRepository.getHoursToExpire();

        // From web page: The value 0 disables this functionality (i.e. no expiry date will be set).
        if (hoursToExpire == 0) {
            return null;
        } else {
            return DateTime.now().plusHours(hoursToExpire);
        }
    }

    private AuthorizationStatus decideStatus(OcppTagActivityRecord record, String idTag, String askingChargeBoxId) {
        if (record == null) {
            log.error("The user with idTag '{}' is INVALID (not present in DB).", idTag);
            return AuthorizationStatus.INVALID;
        }

        if (isBlocked(record)) {
            log.error("The user with idTag '{}' is BLOCKED.", idTag);
            return AuthorizationStatus.BLOCKED;
        }

        if (isExpired(record, DateTime.now())) {
            log.error("The user with idTag '{}' is EXPIRED.", idTag);
            return AuthorizationStatus.EXPIRED;
        }

        // https://github.com/RWTH-i5-IDSG/steve/issues/73
        if (reachedLimitOfActiveTransactions(record)) {
            List<String> txChargeBoxIds = transactionRepository.getChargeBoxIdsOfActiveTransactions(idTag);
            if (!txChargeBoxIds.contains(askingChargeBoxId)) {
                log.warn("The user with idTag '{}' is ALREADY in another transaction(s).", idTag);
                return AuthorizationStatus.CONCURRENT_TX;
            }
        }

        log.debug("The user with idTag '{}' is ACCEPTED.", record.getIdTag());
        return AuthorizationStatus.ACCEPTED;
    }

    private static ocpp.cp._2015._10.AuthorizationStatus decideStatus(OcppTagActivityRecord record, DateTime now) {
        if (isBlocked(record)) {
            return ocpp.cp._2015._10.AuthorizationStatus.BLOCKED;
        } else if (isExpired(record, now)) {
            return ocpp.cp._2015._10.AuthorizationStatus.EXPIRED;
        } else if (reachedLimitOfActiveTransactions(record)) {
            return ocpp.cp._2015._10.AuthorizationStatus.CONCURRENT_TX;
        } else {
            return ocpp.cp._2015._10.AuthorizationStatus.ACCEPTED;
        }
    }

    private static boolean isExpired(OcppTagActivityRecord record, DateTime now) {
        DateTime expiry = record.getExpiryDate();
        return expiry != null && now.isAfter(expiry);
    }

    private static boolean isBlocked(OcppTagActivityRecord record) {
        return getToggle(record) == ConcurrencyToggle.Blocked;
    }

    private static boolean reachedLimitOfActiveTransactions(OcppTagActivityRecord record) {
        ConcurrencyToggle toggle = getToggle(record);
        switch (toggle) {
            case Blocked:
                return true; // for completeness
            case AllowAll:
                return false;
            case AllowAsSpecified:
                int max = record.getMaxActiveTransactionCount();
                long active = record.getActiveTransactionCount();
                return active >= max;
            default:
                throw new RuntimeException("Unexpected ConcurrencyToggle");
        }
    }

    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    private static class AuthorisationDataMapper implements RecordMapper<OcppTagActivityRecord, AuthorizationData> {

        private final DateTime nowDt = DateTime.now();

        @Override
        public AuthorizationData map(OcppTagActivityRecord record) {
            return new AuthorizationData().withIdTag(record.getIdTag())
                                          .withIdTagInfo(
                                                  new ocpp.cp._2015._10.IdTagInfo()
                                                          .withStatus(decideStatus(record, nowDt))
                                                          .withParentIdTag(record.getParentIdTag())
                                                          .withExpiryDate(record.getExpiryDate())
                                          );
        }
    }

    private enum ConcurrencyToggle {
        Blocked, AllowAll, AllowAsSpecified
    }

    private static ConcurrencyToggle getToggle(OcppTagActivityRecord r) {
        int max = r.getMaxActiveTransactionCount();
        if (max == 0) {
            return ConcurrencyToggle.Blocked;
        } else if (max < 0) {
            return ConcurrencyToggle.AllowAll;
        } else {
            return ConcurrencyToggle.AllowAsSpecified;
        }
    }
}
