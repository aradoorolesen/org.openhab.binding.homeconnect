/**
 * Copyright (c) 2010-2019 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.homeconnect.internal.client;

import static java.net.HttpURLConnection.*;
import static org.openhab.binding.homeconnect.internal.HomeConnectBindingConstants.*;
import static org.openhab.binding.homeconnect.internal.client.OkHttpHelper.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.eclipse.smarthome.core.auth.client.oauth2.OAuthClientService;
import org.openhab.binding.homeconnect.internal.client.exception.AuthorizationException;
import org.openhab.binding.homeconnect.internal.client.exception.CommunicationException;
import org.openhab.binding.homeconnect.internal.client.model.AvailableProgram;
import org.openhab.binding.homeconnect.internal.client.model.AvailableProgramOption;
import org.openhab.binding.homeconnect.internal.client.model.Data;
import org.openhab.binding.homeconnect.internal.client.model.HomeAppliance;
import org.openhab.binding.homeconnect.internal.client.model.Option;
import org.openhab.binding.homeconnect.internal.client.model.Program;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Client for Home Connect API.
 *
 * @author Jonas Brüstel - Initial contribution
 *
 */
public class HomeConnectApiClient {
    private static final String ACCEPT = "Accept";
    private static final String CONTENT_TYPE = "Content-Type";
    private static final String BSH_JSON_V1 = "application/vnd.bsh.sdk.v1+json";
    private static final int REQUEST_READ_TIMEOUT = 30;
    private static final int VALUE_TYPE_STRING = 0;
    private static final int VALUE_TYPE_INT = 1;
    private static final int VALUE_TYPE_BOOLEAN = 2;

    private final Logger logger = LoggerFactory.getLogger(HomeConnectApiClient.class);
    private final OkHttpClient client;
    private final String apiUrl;
    private final ConcurrentHashMap<String, List<AvailableProgramOption>> availableProgramOptionsCache;
    private final OAuthClientService oAuthClientService;

    public HomeConnectApiClient(OAuthClientService oAuthClientService, boolean simulated) {
        this.oAuthClientService = oAuthClientService;

        availableProgramOptionsCache = new ConcurrentHashMap<String, List<AvailableProgramOption>>();
        apiUrl = simulated ? API_SIMULATOR_BASE_URL : API_BASE_URL;
        client = OkHttpHelper.builder().readTimeout(REQUEST_READ_TIMEOUT, TimeUnit.SECONDS).build();
    }

    /**
     * Get all home appliances
     *
     * @return list of {@link HomeAppliance}
     * @throws CommunicationException
     * @throws AuthorizationException
     */
    public List<HomeAppliance> getHomeAppliances() throws CommunicationException, AuthorizationException {
        Request request = createGetRequest("/api/homeappliances");

        try (Response response = client.newCall(request).execute()) {
            checkResponseCode(HTTP_OK, response);

            String body = response.body().string();
            if (logger.isDebugEnabled()) {
                logger.debug("[getHomeAppliances()] Response code: {} \n{}", response.code(), formatJsonBody(body));
            }

            return mapToHomeAppliances(body);
        } catch (IOException e) {
            logger.error("IOException: {}", e.getMessage());
            throw new CommunicationException(e);
        }
    }

    /**
     * Get home appliance by id
     *
     * @param haId home appliance id
     * @return {@link HomeAppliance}
     * @throws CommunicationException
     * @throws AuthorizationException
     */
    public HomeAppliance getHomeAppliance(String haId) throws CommunicationException, AuthorizationException {
        Request request = createGetRequest("/api/homeappliances/" + haId);

        try (Response response = client.newCall(request).execute()) {
            checkResponseCode(HTTP_OK, response);
            String body = response.body().string();
            if (logger.isDebugEnabled()) {
                logger.debug("[getHomeAppliance({})] Response code: {},\n{}", haId, response.code(),
                        formatJsonBody(body));
            }

            return mapToHomeAppliance(body);
        } catch (IOException e) {
            logger.error("IOException: {}", e.getMessage());
            throw new CommunicationException(e);
        }
    }

    /**
     * Get power state of device.
     *
     * @param haId home appliance id
     * @return {@link Data}
     * @throws CommunicationException
     * @throws AuthorizationException
     */
    public Data getPowerState(String haId) throws CommunicationException, AuthorizationException {
        return getSetting(haId, "BSH.Common.Setting.PowerState");
    }

    /**
     * Set power state of device.
     *
     * @param haId home appliance id
     * @param state target state
     * @throws CommunicationException
     * @throws AuthorizationException
     */
    public void setPowerState(String haId, String state) throws CommunicationException, AuthorizationException {
        putSettings(haId, new Data("BSH.Common.Setting.PowerState", state, null));
    }

    /**
     * Get setpoint temperature of freezer
     *
     * @param haId home appliance id
     * @return {@link Data}
     * @throws CommunicationException
     * @throws AuthorizationException
     */
    public Data getFreezerSetpointTemperature(String haId) throws CommunicationException, AuthorizationException {
        return getSetting(haId, "Refrigeration.FridgeFreezer.Setting.SetpointTemperatureFreezer");
    }

    /**
     * Set setpoint temperature of freezer
     *
     * @param haId home appliance id
     * @param state new temperature
     * @throws CommunicationException
     * @throws AuthorizationException
     */
    public void setFreezerSetpointTemperature(String haId, String state, String unit)
            throws CommunicationException, AuthorizationException {
        putSettings(haId, new Data("Refrigeration.FridgeFreezer.Setting.SetpointTemperatureFreezer", state, unit),
                VALUE_TYPE_INT);
    }

    /**
     * Get setpoint temperature of fridge
     *
     * @param haId home appliance id
     * @return {@link Data} or null in case of communication error
     * @throws CommunicationException
     * @throws AuthorizationException
     */
    public Data getFridgeSetpointTemperature(String haId) throws CommunicationException, AuthorizationException {
        return getSetting(haId, "Refrigeration.FridgeFreezer.Setting.SetpointTemperatureRefrigerator");
    }

    /**
     * Set setpoint temperature of fridge
     *
     * @param haId home appliance id
     * @param state new temperature
     * @throws CommunicationException
     * @throws AuthorizationException
     */
    public void setFridgeSetpointTemperature(String haId, String state, String unit)
            throws CommunicationException, AuthorizationException {
        putSettings(haId, new Data("Refrigeration.FridgeFreezer.Setting.SetpointTemperatureRefrigerator", state, unit),
                VALUE_TYPE_INT);
    }

    /**
     * Get fridge super mode
     *
     * @param haId home appliance id
     * @return {@link Data}
     * @throws CommunicationException
     * @throws AuthorizationException
     */
    public Data getFridgeSuperMode(String haId) throws CommunicationException, AuthorizationException {
        return getSetting(haId, "Refrigeration.FridgeFreezer.Setting.SuperModeRefrigerator");
    }

    /**
     * Set fridge super mode
     *
     * @param haId home appliance id
     * @param enable enable or disable fridge super mode
     * @throws CommunicationException
     * @throws AuthorizationException
     */
    public void setFridgeSuperMode(String haId, boolean enable) throws CommunicationException, AuthorizationException {
        putSettings(haId,
                new Data("Refrigeration.FridgeFreezer.Setting.SuperModeRefrigerator", String.valueOf(enable), null),
                VALUE_TYPE_BOOLEAN);
    }

    /**
     * Get freezer super mode
     *
     * @param haId home appliance id
     * @return {@link Data}
     * @throws CommunicationException
     * @throws AuthorizationException
     */
    public Data getFreezerSuperMode(String haId) throws CommunicationException, AuthorizationException {
        return getSetting(haId, "Refrigeration.FridgeFreezer.Setting.SuperModeFreezer");
    }

    /**
     * Set freezer super mode
     *
     * @param haId home appliance id
     * @param enable enable or disable freezer super mode
     * @throws CommunicationException
     * @throws AuthorizationException
     */
    public void setFreezerSuperMode(String haId, boolean enable) throws CommunicationException, AuthorizationException {
        putSettings(haId,
                new Data("Refrigeration.FridgeFreezer.Setting.SuperModeFreezer", String.valueOf(enable), null),
                VALUE_TYPE_BOOLEAN);
    }

    /**
     * Get door state of device.
     *
     * @param haId home appliance id
     * @return {@link Data}
     * @throws CommunicationException
     * @throws AuthorizationException
     */
    public Data getDoorState(String haId) throws CommunicationException, AuthorizationException {
        return getStatus(haId, "BSH.Common.Status.DoorState");
    }

    /**
     * Get operation state of device.
     *
     * @param haId home appliance id
     * @return {@link Data}
     * @throws CommunicationException
     * @throws AuthorizationException
     */
    public Data getOperationState(String haId) throws CommunicationException, AuthorizationException {
        return getStatus(haId, "BSH.Common.Status.OperationState");
    }

    /**
     * Is remote start allowed?
     *
     * @param haId haId home appliance id
     * @return
     * @throws CommunicationException
     * @throws AuthorizationException
     */
    public boolean isRemoteControlStartAllowed(String haId) throws CommunicationException, AuthorizationException {
        Data data = getStatus(haId, "BSH.Common.Status.RemoteControlStartAllowed");
        return data != null && "true".equalsIgnoreCase(data.getValue());
    }

    /**
     * Is remote control allowed?
     *
     * @param haId haId home appliance id
     * @return
     * @throws CommunicationException
     * @throws AuthorizationException
     */
    public boolean isRemoteControlActive(String haId) throws CommunicationException, AuthorizationException {
        Data data = getStatus(haId, "BSH.Common.Status.RemoteControlActive");
        return data != null && "true".equalsIgnoreCase(data.getValue());
    }

    /**
     * Is local control allowed?
     *
     * @param haId haId home appliance id
     * @return
     * @throws CommunicationException
     * @throws AuthorizationException
     */
    public boolean isLocalControlActive(String haId) throws CommunicationException, AuthorizationException {
        Data data = getStatus(haId, "BSH.Common.Status.LocalControlActive");
        return data != null && "true".equalsIgnoreCase(data.getValue());
    }

    /**
     * Get active program of device.
     *
     * @param haId home appliance id
     * @return {@link Data} or null if there is no active program
     * @throws CommunicationException
     * @throws AuthorizationException
     */
    public Program getActiveProgram(String haId) throws CommunicationException, AuthorizationException {
        return getProgram(haId, "/api/homeappliances/" + haId + "/programs/active");
    }

    /**
     * Get selected program of device.
     *
     * @param haId home appliance id
     * @return {@link Data} or null if there is no selected program
     * @throws CommunicationException
     * @throws AuthorizationException
     */
    public Program getSelectedProgram(String haId) throws CommunicationException, AuthorizationException {
        return getProgram(haId, "/api/homeappliances/" + haId + "/programs/selected");
    }

    public void setSelectedProgram(String haId, String program) throws CommunicationException, AuthorizationException {
        putData(haId, "/api/homeappliances/" + haId + "/programs/selected", new Data(program, null, null),
                VALUE_TYPE_STRING);
    }

    public void startProgram(String haId, String program) throws CommunicationException, AuthorizationException {
        putData(haId, "/api/homeappliances/" + haId + "/programs/active", new Data(program, null, null),
                VALUE_TYPE_STRING);
    }

    public void startSelectedProgram(String haId) throws CommunicationException, AuthorizationException {
        String selectedProgram = getRaw(haId, "/api/homeappliances/" + haId + "/programs/selected");
        putRaw(haId, "/api/homeappliances/" + haId + "/programs/active", selectedProgram);
    }

    public void setProgramOptions(String haId, String key, String value, String unit, boolean valueAsInt,
            boolean isProgramActive) throws CommunicationException, AuthorizationException {
        String programState = isProgramActive ? "active" : "selected";

        putOption(haId, "/api/homeappliances/" + haId + "/programs/" + programState + "/options",
                new Option(key, value, unit), valueAsInt);
    }

    public void stopProgram(String haId) throws CommunicationException, AuthorizationException {
        sendDelete(haId, "/api/homeappliances/" + haId + "/programs/active");
    }

    public List<AvailableProgram> getPrograms(String haId) throws CommunicationException, AuthorizationException {
        return getAvailablePrograms(haId, "/api/homeappliances/" + haId + "/programs");
    }

    public List<AvailableProgram> getAvailablePrograms(String haId)
            throws CommunicationException, AuthorizationException {
        return getAvailablePrograms(haId, "/api/homeappliances/" + haId + "/programs/available");
    }

    public List<AvailableProgramOption> getProgramOptions(String haId, String programKey)
            throws CommunicationException, AuthorizationException {
        if (availableProgramOptionsCache.containsKey(programKey)) {
            if (logger.isDebugEnabled()) {
                logger.debug("Returning cached options for `{}` \n{}", programKey,
                        availableProgramOptionsCache.get(programKey));
            }
            return availableProgramOptionsCache.get(programKey);
        }

        String path = "/api/homeappliances/" + haId + "/programs/available/" + programKey;

        Request request = createGetRequest(path);

        try (Response response = client.newCall(request).execute()) {
            checkResponseCode(Arrays.asList(HTTP_OK), response);
            String body = response.body().string();
            if (logger.isDebugEnabled()) {
                logger.debug("[getProgramOptions({}, {})] Response code: {}, \n{}", haId, path, response.code(),
                        formatJsonBody(body));
            }

            List<AvailableProgramOption> availableProgramOptions = mapToAvailableProgramOption(body);
            availableProgramOptionsCache.put(programKey, availableProgramOptions);
            return availableProgramOptions;
        } catch (IOException e) {
            logger.error("IOException: {}", e.getMessage());
            throw new CommunicationException(e);
        }
    }

    private Data getSetting(String haId, String setting) throws CommunicationException, AuthorizationException {
        return getData(haId, "/api/homeappliances/" + haId + "/settings/" + setting);
    }

    private void putSettings(String haId, Data data) throws CommunicationException, AuthorizationException {
        putSettings(haId, data, VALUE_TYPE_STRING);
    }

    private void putSettings(String haId, Data data, int valueType)
            throws CommunicationException, AuthorizationException {
        putData(haId, "/api/homeappliances/" + haId + "/settings/" + data.getName(), data, valueType);
    }

    private Data getStatus(String haId, String status) throws CommunicationException, AuthorizationException {
        return getData(haId, "/api/homeappliances/" + haId + "/status/" + status);
    }

    private String getRaw(String haId, String path) throws CommunicationException, AuthorizationException {
        Request request = createGetRequest(path);

        try (Response response = client.newCall(request).execute()) {
            checkResponseCode(Arrays.asList(HTTP_OK), response);
            String body = response.body().string();
            if (logger.isDebugEnabled()) {
                logger.debug("[getRaw({}, {})] Response code: {}, \n{}", haId, path, response.code(),
                        formatJsonBody(body));
            }

            if (response.code() == HTTP_OK) {
                return body;
            }
        } catch (IOException e) {
            logger.error("IOException: {}", e.getMessage());
            throw new CommunicationException(e);
        }
        return null;
    }

    private void putRaw(String haId, String path, String requestBodyPayload)
            throws CommunicationException, AuthorizationException {

        if (logger.isDebugEnabled()) {
            logger.debug("Send data(PUT {}) \n{}", path, requestBodyPayload);
        }

        MediaType JSON = MediaType.parse(BSH_JSON_V1);
        RequestBody requestBody = RequestBody.create(JSON, requestBodyPayload.getBytes(StandardCharsets.UTF_8));

        Request request = requestBuilder(oAuthClientService).url(apiUrl + path).header(CONTENT_TYPE, BSH_JSON_V1)
                .header(ACCEPT, BSH_JSON_V1).put(requestBody).build();

        try (Response response = client.newCall(request).execute()) {
            checkResponseCode(HTTP_NO_CONTENT, response);
            String body = response.body().string();
            if (logger.isDebugEnabled()) {
                logger.debug("[putRaw({}, {}, {})] Response code: {} \n{}", haId, path, requestBodyPayload,
                        response.code(), formatJsonBody(body));
            }

        } catch (IOException e) {
            logger.error("IOException: {}", e.getMessage());
            throw new CommunicationException(e);
        }
    }

    private Program getProgram(String haId, String path) throws CommunicationException, AuthorizationException {
        Request request = createGetRequest(path);

        try (Response response = client.newCall(request).execute()) {
            checkResponseCode(Arrays.asList(HTTP_OK, HTTP_NOT_FOUND), response);
            String body = response.body().string();
            if (logger.isDebugEnabled()) {
                logger.debug("[getProgram({}, {})] Response code: {}, \n{}", haId, path, response.code(),
                        formatJsonBody(body));
            }

            if (response.code() == HTTP_OK) {
                return mapToProgram(body);
            }
        } catch (IOException e) {
            logger.error("IOException: {}", e.getMessage());
            throw new CommunicationException(e);
        }
        return null;
    }

    private List<AvailableProgram> getAvailablePrograms(String haId, String path)
            throws CommunicationException, AuthorizationException {
        Request request = createGetRequest(path);

        try (Response response = client.newCall(request).execute()) {
            checkResponseCode(Arrays.asList(HTTP_OK), response);
            String body = response.body().string();
            if (logger.isDebugEnabled()) {
                logger.debug("[getProgram({}, {})] Response code: {}, \n{}", haId, path, response.code(),
                        formatJsonBody(body));
            }

            return mapToAvailablePrograms(body);
        } catch (IOException e) {
            logger.error("IOException: {}", e.getMessage());
            throw new CommunicationException(e);
        }
    }

    private void sendDelete(String haId, String path) throws CommunicationException, AuthorizationException {
        Request request = requestBuilder(oAuthClientService).url(apiUrl + path).header(ACCEPT, BSH_JSON_V1).delete()
                .build();

        try (Response response = client.newCall(request).execute()) {
            checkResponseCode(HTTP_NO_CONTENT, response);
            if (logger.isDebugEnabled()) {
                logger.debug("[sendDelete({}, {})] Response code: {}", haId, path, response.code());
            }

        } catch (IOException e) {
            logger.error("IOException: {}", e.getMessage());
            throw new CommunicationException(e);
        }
    }

    private Data getData(String haId, String path) throws CommunicationException, AuthorizationException {
        Request request = createGetRequest(path);

        try (Response response = client.newCall(request).execute()) {
            checkResponseCode(HTTP_OK, response);
            String body = response.body().string();
            if (logger.isDebugEnabled()) {
                logger.debug("[getData({}, {})] Response code: {}, \n{}", haId, path, response.code(),
                        formatJsonBody(body));
            }

            return mapToState(body);
        } catch (IOException e) {
            logger.error("IOException: {}", e.getMessage());
            throw new CommunicationException(e);
        }
    }

    private void putData(String haId, String path, Data data, int valueType)
            throws CommunicationException, AuthorizationException {
        JsonObject innerObject = new JsonObject();
        innerObject.addProperty("key", data.getName());

        if (data.getValue() != null) {
            if (valueType == VALUE_TYPE_INT) {
                innerObject.addProperty("value", Integer.valueOf(data.getValue()));
            } else if (valueType == VALUE_TYPE_BOOLEAN) {
                innerObject.addProperty("value", Boolean.valueOf(data.getValue()));
            } else {
                innerObject.addProperty("value", data.getValue());
            }
        }

        if (data.getUnit() != null) {
            innerObject.addProperty("unit", data.getUnit());
        }

        JsonObject dataObject = new JsonObject();
        dataObject.add("data", innerObject);
        String requestBodyPayload = dataObject.toString();

        if (logger.isDebugEnabled()) {
            logger.debug("Send data(PUT {}) \n{}", path, requestBodyPayload);
        }

        MediaType JSON = MediaType.parse(BSH_JSON_V1);
        RequestBody requestBody = RequestBody.create(JSON, requestBodyPayload.getBytes(StandardCharsets.UTF_8));

        Request request = requestBuilder(oAuthClientService).url(apiUrl + path).header(CONTENT_TYPE, BSH_JSON_V1)
                .header(ACCEPT, BSH_JSON_V1).put(requestBody).build();

        try (Response response = client.newCall(request).execute()) {
            checkResponseCode(HTTP_NO_CONTENT, response);
            String body = response.body().string();
            if (logger.isDebugEnabled()) {
                logger.debug("[putData({}, {}, {})] Response code: {} \n{}", haId, path, data, response.code(),
                        formatJsonBody(body));
            }

        } catch (IOException e) {
            logger.error("IOException: {}", e.getMessage());
            throw new CommunicationException(e);
        }
    }

    private void putOption(String haId, String path, Option option, boolean asInt)
            throws CommunicationException, AuthorizationException {
        JsonObject innerObject = new JsonObject();
        innerObject.addProperty("key", option.getKey());

        if (option.getValue() != null) {
            if (asInt) {
                innerObject.addProperty("value", Integer.valueOf(option.getValue()));
            } else {
                innerObject.addProperty("value", option.getValue());
            }
        }

        if (option.getUnit() != null) {
            innerObject.addProperty("unit", option.getUnit());
        }

        JsonArray optionsArray = new JsonArray();
        optionsArray.add(innerObject);

        JsonObject optionsObject = new JsonObject();
        optionsObject.add("options", optionsArray);

        JsonObject dataObject = new JsonObject();
        dataObject.add("data", optionsObject);

        String requestBodyPayload = dataObject.toString();

        if (logger.isDebugEnabled()) {
            logger.debug("Send data(PUT {}) \n{}", path, requestBodyPayload);
        }

        MediaType JSON = MediaType.parse(BSH_JSON_V1);
        RequestBody requestBody = RequestBody.create(JSON, requestBodyPayload.getBytes(StandardCharsets.UTF_8));

        Request request = requestBuilder(oAuthClientService).url(apiUrl + path).header(CONTENT_TYPE, BSH_JSON_V1)
                .header(ACCEPT, BSH_JSON_V1).put(requestBody).build();

        try (Response response = client.newCall(request).execute()) {
            checkResponseCode(HTTP_NO_CONTENT, response);
            String body = response.body().string();
            if (logger.isDebugEnabled()) {
                logger.debug("[putOption({}, {}, {})] Response code: {} \n{}", haId, path, option, response.code(),
                        formatJsonBody(body));
            }

        } catch (IOException e) {
            logger.error("IOException: {}", e.getMessage());
            throw new CommunicationException(e);
        }
    }

    private void checkResponseCode(int desiredCode, Response response)
            throws CommunicationException, AuthorizationException {
        checkResponseCode(Arrays.asList(desiredCode), response);
    }

    private void checkResponseCode(List<Integer> desiredCodes, Response response)
            throws CommunicationException, AuthorizationException {
        if (!desiredCodes.contains(HTTP_UNAUTHORIZED) && response.code() == HTTP_UNAUTHORIZED) {
            logger.debug("[oAuth] Current access token is invalid.");
            throw new AuthorizationException("Token invalid!");
        }

        if (!desiredCodes.contains(response.code())) {
            int code = response.code();
            String message = response.message();
            String body = "";
            try {
                body = response.body().string();
            } catch (IOException e) {
                logger.error("Could not get HTTP response body as string", e);
            }

            throw new CommunicationException(code, message, body);
        }
    }

    private Program mapToProgram(String json) {
        final ArrayList<Option> optionList = new ArrayList<>();
        Program result = null;

        JsonObject responseObject = new JsonParser().parse(json).getAsJsonObject();

        JsonObject data = responseObject.getAsJsonObject("data");
        result = new Program(data.get("key").getAsString(), optionList);
        JsonArray options = data.getAsJsonArray("options");

        options.forEach(option -> {
            JsonObject obj = (JsonObject) option;

            String key = obj.get("key") != null ? obj.get("key").getAsString() : null;
            String value = obj.get("value") != null && !obj.get("value").isJsonNull() ? obj.get("value").getAsString()
                    : null;
            String unit = obj.get("unit") != null ? obj.get("unit").getAsString() : null;

            optionList.add(new Option(key, value, unit));
        });

        return result;
    }

    private List<AvailableProgram> mapToAvailablePrograms(String json) {
        ArrayList<AvailableProgram> result = new ArrayList<>();

        try {
            JsonObject responseObject = new JsonParser().parse(json).getAsJsonObject();

            JsonArray programs = responseObject.getAsJsonObject("data").getAsJsonArray("programs");
            programs.forEach(program -> {
                JsonObject obj = (JsonObject) program;
                String key = obj.get("key") != null ? obj.get("key").getAsString() : null;
                JsonObject constraints = obj.getAsJsonObject("constraints");
                boolean available = constraints.get("available") != null ? constraints.get("available").getAsBoolean()
                        : false;
                String execution = constraints.get("execution") != null ? constraints.get("execution").getAsString()
                        : null;

                result.add(new AvailableProgram(key, available, execution));
            });
        } catch (Exception e) {
            logger.error("Could not parse available programs response! {}", e.getMessage());
        }

        return result;
    }

    private List<AvailableProgramOption> mapToAvailableProgramOption(String json) {
        ArrayList<AvailableProgramOption> result = new ArrayList<>();

        try {
            JsonObject responseObject = new JsonParser().parse(json).getAsJsonObject();

            JsonArray options = responseObject.getAsJsonObject("data").getAsJsonArray("options");
            options.forEach(option -> {
                JsonObject obj = (JsonObject) option;
                String key = obj.get("key") != null ? obj.get("key").getAsString() : null;
                ArrayList<String> allowedValues = new ArrayList<>();
                obj.getAsJsonObject("constraints").getAsJsonArray("allowedvalues")
                        .forEach(value -> allowedValues.add(value.getAsString()));

                result.add(new AvailableProgramOption(key, allowedValues));
            });
        } catch (Exception e) {
            logger.error("Could not parse available program options response! {}", e.getMessage());
        }

        return result;
    }

    private HomeAppliance mapToHomeAppliance(String json) {
        JsonObject responseObject = new JsonParser().parse(json).getAsJsonObject();

        JsonObject data = responseObject.getAsJsonObject("data");

        return new HomeAppliance(data.get("haId").getAsString(), data.get("name").getAsString(),
                data.get("brand").getAsString(), data.get("vib").getAsString(), data.get("connected").getAsBoolean(),
                data.get("type").getAsString(), data.get("enumber").getAsString());
    }

    private ArrayList<HomeAppliance> mapToHomeAppliances(String json) {
        final ArrayList<HomeAppliance> result = new ArrayList<>();
        JsonObject responseObject = new JsonParser().parse(json).getAsJsonObject();

        JsonObject data = responseObject.getAsJsonObject("data");
        JsonArray homeappliances = data.getAsJsonArray("homeappliances");

        homeappliances.forEach(appliance -> {
            JsonObject obj = (JsonObject) appliance;

            result.add(new HomeAppliance(obj.get("haId").getAsString(), obj.get("name").getAsString(),
                    obj.get("brand").getAsString(), obj.get("vib").getAsString(), obj.get("connected").getAsBoolean(),
                    obj.get("type").getAsString(), obj.get("enumber").getAsString()));
        });

        return result;
    }

    private Data mapToState(String json) {
        JsonObject responseObject = new JsonParser().parse(json).getAsJsonObject();

        JsonObject data = responseObject.getAsJsonObject("data");

        String unit = data.get("unit") != null ? data.get("unit").getAsString() : null;

        return new Data(data.get("key").getAsString(), data.get("value").getAsString(), unit);
    }

    private Request createGetRequest(String path) throws AuthorizationException, CommunicationException {
        return requestBuilder(oAuthClientService).url(apiUrl + path).header(ACCEPT, BSH_JSON_V1).get().build();
    }
}
