/*
 * Copyright 2016 - 2022 Anton Tananaev (anton@traccar.org)
 * Copyright 2017 Andrey Kunitsyn (andrey@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.handler.events;

import io.netty.channel.ChannelHandler;
import org.traccar.helper.model.PositionUtil;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.reports.common.TripsConfig;
import org.traccar.session.ConnectionManager;
import org.traccar.session.DeviceState;
import org.traccar.session.cache.CacheManager;

import javax.inject.Inject;
import java.util.Collections;
import java.util.Map;

@ChannelHandler.Sharable
public class MotionEventHandler extends BaseEventHandler {

    private final CacheManager cacheManager;
    private final ConnectionManager connectionManager;
    private final TripsConfig tripsConfig;

    @Inject
    public MotionEventHandler(
            CacheManager cacheManager, ConnectionManager connectionManager, TripsConfig tripsConfig) {
        this.cacheManager = cacheManager;
        this.connectionManager = connectionManager;
        this.tripsConfig = tripsConfig;
    }

    private Map<Event, Position> newEvent(DeviceState deviceState, boolean newMotion) {
        String eventType = newMotion ? Event.TYPE_DEVICE_MOVING : Event.TYPE_DEVICE_STOPPED;
        Position position = deviceState.getMotionPosition();
        Event event = new Event(eventType, position);
        deviceState.setMotionState(newMotion);
        deviceState.setMotionPosition(null);
        return Collections.singletonMap(event, position);
    }

    public Map<Event, Position> updateMotionState(DeviceState deviceState) {
        Map<Event, Position> result = null;
        if (deviceState.getMotionState() != null && deviceState.getMotionPosition() != null) {
            boolean newMotion = !deviceState.getMotionState();
            Position motionPosition = deviceState.getMotionPosition();
            long currentTime = System.currentTimeMillis();
            long motionTime = motionPosition.getFixTime().getTime()
                    + (newMotion ? tripsConfig.getMinimalTripDuration() : tripsConfig.getMinimalParkingDuration());
            if (motionTime <= currentTime) {
                result = newEvent(deviceState, newMotion);
            }
        }
        return result;
    }

    public Map<Event, Position> updateMotionState(DeviceState deviceState, Position position) {
        return updateMotionState(deviceState, position, position.getBoolean(Position.KEY_MOTION));
    }

    public Map<Event, Position> updateMotionState(DeviceState deviceState, Position position, boolean newMotion) {
        Map<Event, Position> result = null;
        Boolean oldMotion = deviceState.getMotionState();

        long currentTime = position.getFixTime().getTime();
        if (newMotion != oldMotion) {
            if (deviceState.getMotionPosition() == null) {
                deviceState.setMotionPosition(position);
            }
        } else {
            deviceState.setMotionPosition(null);
        }

        Position motionPosition = deviceState.getMotionPosition();
        if (motionPosition != null) {
            long motionTime = motionPosition.getFixTime().getTime();
            double distance = PositionUtil.calculateDistance(motionPosition, position, false);
            Boolean ignition = null;
            if (tripsConfig.getUseIgnition()
                    && position.getAttributes().containsKey(Position.KEY_IGNITION)) {
                ignition = position.getBoolean(Position.KEY_IGNITION);
            }
            if (newMotion) {
                if (motionTime + tripsConfig.getMinimalTripDuration() <= currentTime
                        || distance >= tripsConfig.getMinimalTripDistance()) {
                    result = newEvent(deviceState, newMotion);
                }
            } else {
                if (motionTime + tripsConfig.getMinimalParkingDuration() <= currentTime
                        || ignition != null && !ignition) {
                    result = newEvent(deviceState, newMotion);
                }
            }
        }
        return result;
    }

    @Override
    protected Map<Event, Position> analyzePosition(Position position) {

        long deviceId = position.getDeviceId();
        Device device = cacheManager.getObject(Device.class, deviceId);
        if (device == null) {
            return null;
        }
        if (!PositionUtil.isLatest(cacheManager, position)
                || !tripsConfig.getProcessInvalidPositions() && !position.getValid()) {
            return null;
        }

        Map<Event, Position> result = null;
        DeviceState deviceState = connectionManager.getDeviceState(deviceId);

        if (deviceState.getMotionState() == null) {
            deviceState.setMotionState(position.getBoolean(Position.KEY_MOTION));
        } else {
            result = updateMotionState(deviceState, position);
        }
        connectionManager.setDeviceState(deviceId, deviceState);
        return result;
    }

}
