package com.biecuoguo.push.internal;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Persistent provider progress. Every method releases its transaction before any provider network call. */
@Service
public class PushDeliveryProgress {
    public static final int PAGE_LIMIT = 64;
    private final PushDeliveryProgressMapper mapper;

    public PushDeliveryProgress(PushDeliveryProgressMapper mapper) { this.mapper = mapper; }

    public record Batch(String eventId, long userId, long maxTokenId) {}

    public record Target(long id, String token) {}

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public Batch open(String eventId, long userId) {
        checkIdentity(eventId, userId);
        mapper.open(eventId, userId);
        var row = requireOwner(mapper.lockBatch(eventId), userId);
        return new Batch(row.eventId(), row.userId(), row.snapshotMaxTokenId());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public List<Target> pending(Batch batch, int limit) {
        if (limit < 1 || limit > PAGE_LIMIT) { throw new IllegalArgumentException("Unsupported push page size"); }
        checkIdentity(batch.eventId(), batch.userId());
        var row = requireOwner(mapper.lockBatch(batch.eventId()), batch.userId());
        List<Target> targets = mapper.pending(row.eventId(), row.userId(), row.nextAfterTokenId(), limit);
        if (targets.isEmpty() && row.nextAfterTokenId() > 0) {
            targets = mapper.pending(row.eventId(), row.userId(), 0, limit);
        }
        if (!targets.isEmpty()) {
            mapper.advance(row.eventId(), row.userId(), targets.getLast().id());
        }
        return targets;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, readOnly = true)
    public boolean isCurrentTarget(Batch batch, Target target) {
        requireBatch(batch);
        return mapper.isCurrentTarget(batch.eventId(), batch.userId(), target.id(), target.token());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, readOnly = true)
    public boolean hasPending(Batch batch) {
        requireBatch(batch);
        return mapper.hasPending(batch.eventId(), batch.userId());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void accepted(Batch batch, long deviceId) { receipt(batch, deviceId, "ACCEPTED"); }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void invalid(Batch batch, long deviceId) { receipt(batch, deviceId, "INVALID"); }

    private void receipt(Batch batch, long deviceId, String outcome) {
        var row = requireBatch(batch);
        if (deviceId < 1 || deviceId > row.snapshotMaxTokenId()) {
            throw new IllegalArgumentException("Device is outside the push batch snapshot");
        }
        mapper.receipt(row.eventId(), row.userId(), deviceId, outcome);
    }

    private PushDeliveryProgressMapper.BatchRow requireBatch(Batch batch) {
        checkIdentity(batch.eventId(), batch.userId());
        return requireOwner(mapper.batch(batch.eventId()), batch.userId());
    }

    private static PushDeliveryProgressMapper.BatchRow requireOwner(PushDeliveryProgressMapper.BatchRow row, long userId) {
        if (row == null || row.userId() != userId) {
            throw new IllegalArgumentException("Push event ownership does not match");
        }
        return row;
    }

    private static void checkIdentity(String eventId, long userId) {
        if (eventId == null || !eventId.matches("[0-9a-f]{32}") || userId < 1) {
            throw new IllegalArgumentException("Invalid push event identity");
        }
    }
}
