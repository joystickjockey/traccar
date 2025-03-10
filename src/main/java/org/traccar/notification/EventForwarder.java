/*
 * Copyright 2016 - 2022 Anton Tananaev (anton@traccar.org)
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
package org.traccar.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Geofence;
import org.traccar.model.Maintenance;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.InvocationCallback;
import java.util.HashMap;
import java.util.Map;

public class EventForwarder {

    private static final Logger LOGGER = LoggerFactory.getLogger(EventForwarder.class);

    private final String url;
    private final String header;

    private final Client client;
    private final CacheManager cacheManager;

    public EventForwarder(Config config, Client client, CacheManager cacheManager) {
        this.client = client;
        this.cacheManager = cacheManager;
        url = config.getString(Keys.EVENT_FORWARD_URL);
        header = config.getString(Keys.EVENT_FORWARD_HEADERS);
    }

    private static final String KEY_POSITION = "position";
    private static final String KEY_EVENT = "event";
    private static final String KEY_GEOFENCE = "geofence";
    private static final String KEY_DEVICE = "device";
    private static final String KEY_MAINTENANCE = "maintenance";

    public final void forwardEvent(Event event, Position position) {

        Invocation.Builder requestBuilder = client.target(url).request();

        if (header != null && !header.isEmpty()) {
            for (String line: header.split("\\r?\\n")) {
                String[] values = line.split(":", 2);
                requestBuilder.header(values[0].trim(), values[1].trim());
            }
        }

        LOGGER.debug("Event forwarding initiated");
        requestBuilder.async().post(
                Entity.json(preparePayload(event, position)), new InvocationCallback<Object>() {
                    @Override
                    public void completed(Object o) {
                        LOGGER.debug("Event forwarding succeeded");
                    }

                    @Override
                    public void failed(Throwable throwable) {
                        LOGGER.warn("Event forwarding failed", throwable);
                    }
                });
    }

    protected Map<String, Object> preparePayload(Event event, Position position) {
        Map<String, Object> data = new HashMap<>();
        data.put(KEY_EVENT, event);
        if (position != null) {
            data.put(KEY_POSITION, position);
        }
        Device device = cacheManager.getObject(Device.class, event.getDeviceId());
        if (device != null) {
            data.put(KEY_DEVICE, device);
        }
        if (event.getGeofenceId() != 0) {
            Geofence geofence = cacheManager.getObject(Geofence.class, event.getGeofenceId());
            if (geofence != null) {
                data.put(KEY_GEOFENCE, geofence);
            }
        }
        if (event.getMaintenanceId() != 0) {
            Maintenance maintenance = cacheManager.getObject(Maintenance.class, event.getMaintenanceId());
            if (maintenance != null) {
                data.put(KEY_MAINTENANCE, maintenance);
            }
        }
        return data;
    }

}
