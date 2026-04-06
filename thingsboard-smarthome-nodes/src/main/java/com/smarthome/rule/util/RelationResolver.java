package com.smarthome.rule.util;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import org.thingsboard.common.util.ListeningExecutor;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.id.AssetId;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.relation.EntityRelation;
import org.thingsboard.server.common.data.relation.RelationTypeGroup;
import org.thingsboard.server.dao.relation.RelationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Resolves room or home assets to their list of child Device IDs,
 * walking the "Contains" relations in the SmartHome asset hierarchy:
 *
 *   Home Asset --Contains--> Room Asset --Contains--> Device
 *   Home Asset --Contains--> Device   (unassigned devices)
 */
public class RelationResolver {

    private static final Logger log = LoggerFactory.getLogger(RelationResolver.class);

    private final RelationService    relationService;
    private final TenantId           tenantId;
    private final String             relationTypeFilter;  // typically "Contains"
    private final ListeningExecutor  executor;

    public RelationResolver(RelationService relationService, TenantId tenantId,
                            String relationTypeFilter, ListeningExecutor executor) {
        this.relationService    = relationService;
        this.tenantId           = tenantId;
        this.relationTypeFilter = relationTypeFilter;
        this.executor           = executor;
    }

    /**
     * Resolve all devices reachable via "Contains" relations from any asset entity.
     * Works for both Room (flat: asset→devices) and Home (nested: asset→rooms→devices).
     * Use this when the target asset ID comes from msg.getOriginator().
     */
    public ListenableFuture<List<DeviceId>> resolveDevicesFromEntity(UUID entityId) {
        return resolveDevicesInHome(entityId);
    }

    /**
     * Resolve devices directly contained in a ROOM asset.
     */
    public ListenableFuture<List<DeviceId>> resolveDevicesInRoom(UUID roomAssetId) {
        AssetId assetId = new AssetId(roomAssetId);

        ListenableFuture<List<EntityRelation>> relsFuture =
                relationService.findByFromAndTypeAsync(tenantId, assetId,
                        relationTypeFilter, RelationTypeGroup.COMMON);

        return Futures.transform(relsFuture, relations -> {
            if (relations == null) return List.of();
            return relations.stream()
                    .filter(r -> r.getTo().getEntityType() == EntityType.DEVICE)
                    .map(r -> new DeviceId(r.getTo().getId()))
                    .collect(Collectors.toList());
        }, executor);
    }

    /**
     * Resolve all devices in a HOME asset (walks one level deep: home → rooms → devices).
     * Also includes devices directly under the home (unassigned rooms).
     */
    public ListenableFuture<List<DeviceId>> resolveDevicesInHome(UUID homeAssetId) {
        AssetId homeId = new AssetId(homeAssetId);

        ListenableFuture<List<EntityRelation>> homeRelsFuture =
                relationService.findByFromAndTypeAsync(tenantId, homeId,
                        relationTypeFilter, RelationTypeGroup.COMMON);

        return Futures.transformAsync(homeRelsFuture, homeRelations -> {
            if (homeRelations == null) return Futures.immediateFuture(List.of());

            List<DeviceId> directDevices = new ArrayList<>();
            List<UUID>     roomIds       = new ArrayList<>();

            for (EntityRelation rel : homeRelations) {
                if (rel.getTo().getEntityType() == EntityType.DEVICE) {
                    directDevices.add(new DeviceId(rel.getTo().getId()));
                } else if (rel.getTo().getEntityType() == EntityType.ASSET) {
                    roomIds.add(rel.getTo().getId());
                }
            }

            if (roomIds.isEmpty()) {
                return Futures.immediateFuture(directDevices);
            }

            List<ListenableFuture<List<DeviceId>>> roomFutures = roomIds.stream()
                    .map(this::resolveDevicesInRoom)
                    .collect(Collectors.toList());

            ListenableFuture<List<List<DeviceId>>> allRoomsFuture = Futures.allAsList(roomFutures);

            return Futures.transform(allRoomsFuture, roomDeviceLists -> {
                List<DeviceId> all = new ArrayList<>(directDevices);
                if (roomDeviceLists != null) {
                    for (List<DeviceId> roomDevices : roomDeviceLists) {
                        if (roomDevices != null) all.addAll(roomDevices);
                    }
                }
                return all;
            }, executor);

        }, executor);
    }
}
