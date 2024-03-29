package aclusterllc.adta;

import org.json.JSONArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.text.ParseException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class Server implements Runnable {

    private Thread worker;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean stopped = new AtomicBoolean(true);

    private final List<ServerListener> serverListeners = new ArrayList<>();
    private final List<ClientListListener> clientListListeners = new ArrayList<>();
    private static ConcurrentHashMap<String, SocketChannel> clientList = new ConcurrentHashMap<>();
    public CustomMap<Integer, Client> cmClients  = new CustomMap<>();
    Selector selector;
    ServerSocketChannel serverSocket;
    ByteBuffer buffer;
    private final ServerDBHandler serverDBHandler = new ServerDBHandler();
    Logger logger = LoggerFactory.getLogger(Server.class);


    public Server() {
    }

    public void start() {
        logger.info("HMI Server Started");
        worker = new Thread(this);
        try {
            selector = Selector.open();
            serverSocket = ServerSocketChannel.open();
            //serverSocket.bind(new InetSocketAddress(ServerConstants.configuration.get("hmi_server_ip"), Integer.parseInt(ServerConstants.configuration.get("hmi_server_port"))));
            serverSocket.bind(new InetSocketAddress(Integer.parseInt(ServerConstants.configuration.get("hmi_server_port"))));
            serverSocket.configureBlocking(false);
            serverSocket.register(selector, SelectionKey.OP_ACCEPT);
        } catch (IOException e) {
            logger.error(ServerConstants.configuration.get("hmi_server_ip") + " - " + Integer.parseInt(ServerConstants.configuration.get("hmi_server_port")));
            logger.error(e.toString());
            //e.printStackTrace();
        }
        buffer = ByteBuffer.allocate(10240000);
        worker.start();
    }

    public void interrupt() {
		running.set(false);
		notifyListeners("Self", "", "Disconnected from server");
        worker.interrupt();
    }

    boolean isRunning() {
        return running.get();
    }

    boolean isStopped() {
        return stopped.get();
    }

	public void run() {

		running.set(true);
        stopped.set(false);

		while (running.get()) {
		    //System.out.println("Socket running");
            try {
                selector.select();
                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                Iterator<SelectionKey> iter = selectedKeys.iterator();
                while (iter.hasNext()) {
                    SelectionKey key = iter.next();

                    if (key.isValid() && key.isAcceptable()) {
                        register(selector, serverSocket);
                    }

                    if (key.isValid() && key.isReadable()) {
                        readMessage(key);
                    }

                    iter.remove();
                }

            } catch (IOException e) {
                logger.error(e.toString());
                //e.printStackTrace();
            }
		}

		stopped.set(true);
	}

	private void register(Selector selector, ServerSocketChannel serverSocket) {

        SocketChannel client = null;
        String clientName;
        try {
            client = serverSocket.accept();

            client.configureBlocking(false);
            client.register(selector, SelectionKey.OP_READ);
            clientName = client.getRemoteAddress().toString().split("/")[1];
            clientList.put(clientName, client);
            notifyClientListListeners(clientName, 1);
            notifyListeners(clientName, "", "Client connected");
            logger.info("HMI connected from " + clientName);
        } catch (IOException e) {
            //e.printStackTrace();
            logger.error(e.toString());
        }
    }

    public void disconnectClient(SocketChannel client) {

        String clientName = null;
        try {
            clientName = client.getRemoteAddress().toString().split("/")[1];
            client.close();
            clientList.remove(clientName);
            notifyClientListListeners(clientName, 0);
            notifyListeners(clientName, "", "Connection terminated");
            logger.error("HMI disconnected from " + clientName);
        } catch (IOException e) {
            //e.printStackTrace();
            logger.error(e.toString());
        }
    }


    private void readMessage(SelectionKey key) {
        SocketChannel client = (SocketChannel) key.channel();
        String clientName = null;
        try {
            clientName = client.getRemoteAddress().toString().split("/")[1];
        } catch (IOException e) {
            logger.error(e.toString());
            //e.printStackTrace();
        }

        buffer.clear();

        int numRead = 0;
        try {
            numRead = client.read(buffer);
        } catch (IOException e) {
            logger.error(e.toString());
            // The remote forcibly closed the connection, cancel
            // the selection key and close the channel.
            disconnectClient(client);

            return;
        }

        if (numRead == -1) {
            // Remote entity shut the socket down cleanly. Do the
            // same from our end and cancel the channel.
            disconnectClient(client);

            return;
        }

        byte[] b = new byte[buffer.position()];
        buffer.flip();
        buffer.get(b);

        String data = new String( b, StandardCharsets.UTF_8 );

        String startTag="<begin>";
        String endTag="</begin>";
        int startPos=data.indexOf(startTag);
        int endPos=data.indexOf(endTag);
        while (startPos>-1 && endPos>-1){
            if(startPos>0){
                logger.warn("[START_POS_ERROR] Message did not started with begin");
                logger.warn("[MESSAGE]"+data);
            }
            if(startPos>endPos){
                logger.warn("[END_POS_ERROR] End tag found before start tag.");
                logger.warn("[MESSAGE]"+data);
                data=data.substring(startPos);
            }
            else{
                String messageString=data.substring(startPos+startTag.length(),endPos);
                try {
                    JSONObject jo = new JSONObject(messageString);
                    if(jo.get("req") != null) {
                        processClientRequest(clientName, jo);
                    }
                }
                catch (JSONException | ParseException e) {
                    logger.error(e.toString());
                }
                data=data.substring(endPos+endTag.length());
            }
            startPos=data.indexOf(startTag);
            endPos=data.indexOf(endTag);
        }

    }

    public void sendWelcomeMessage(String clientName) {
        if((clientList.size() > 0) && (clientList.get(clientName) != null)) {
            String msg = "{\"test\": \"msg\"}";
            sendMessage(clientName, msg);
        } else {
            System.err.println("Client not found");
        }
    }

    public void processClientRequest(String clientName, JSONObject jsonObject) throws ParseException {
        try {
            String request = jsonObject.get("req").toString();
            JSONArray requestData = new JSONArray();
            if (jsonObject.has("requestData")) {
                requestData = jsonObject.getJSONArray("requestData");
            }
            if (requestData.length() > 0) {
                JSONObject params = new JSONObject();
                if (jsonObject.has("params")) {
                    params = jsonObject.getJSONObject("params");
                }
                int machine_id=0;
                if(params.has("machine_id")){machine_id=params.getInt("machine_id");}

                JSONObject response=new JSONObject();
                response.put("request",request);
                response.put("params",params);
                Connection connection=DataSource.getConnection();
                JSONObject responseData=new JSONObject();
                for(int i=0;i<requestData.length();i++){
                    JSONObject requestFunction=requestData.getJSONObject(i);
                    String requestFunctionName=requestFunction.getString("name");
                    switch (requestFunctionName) {
                        case "active_alarms": {
                            responseData.put(requestFunctionName,DatabaseHelper.getActiveAlarms(connection,machine_id));
                            break;
                        }
                        case "alarms_history": {
                            responseData.put(requestFunctionName,DatabaseHelper.getAlarmsHistory(connection,machine_id,requestFunction.getJSONObject("params")));
                            break;
                        }
                        case "device_states": {
                            responseData.put(requestFunctionName,DatabaseHelper.getDeviceStates(connection,machine_id));
                            break;
                        }
                        case "input_states": {
                            responseData.put(requestFunctionName,DatabaseHelper.getInputStates(connection,machine_id));
                            break;
                        }
                        case "import_parameters_value": {
                            JSONArray csvData=requestFunction.getJSONObject("params").getJSONArray("csvData");
                            JSONObject oldParamData=DatabaseHelper.getParameterValues(connection,machine_id);
                            int totalRowChanged=0;
                            try {
                                for(int tempI=0;tempI<csvData.length();tempI++){
                                    JSONObject row=csvData.getJSONObject(tempI);
                                    int temp_machine_id=row.getInt("machine_id");
                                    int temp_param_id=row.getInt("param_id");
                                    int temp_value=row.getInt("value");
                                    if(oldParamData.has(temp_machine_id+"_"+temp_param_id)){
                                        if(oldParamData.getJSONObject(temp_machine_id+"_"+temp_param_id).getInt("value")!= temp_value){
                                            totalRowChanged++;
                                            byte[] messageBytes = new byte[]{
                                                    0, 0, 0, 115, 0, 0, 0, 20, 0, 0, 0, 0,
                                                    (byte) (temp_param_id >> 24), (byte) (temp_param_id >> 16), (byte) (temp_param_id >> 8), (byte) (temp_param_id),
                                                    (byte) (temp_value >> 24), (byte) (temp_value >> 16), (byte) (temp_value >> 8), (byte) (temp_value)
                                            };
                                            Client client = cmClients.get(machine_id);
                                            client.sendBytes(messageBytes);
                                        }
                                    }
                                }
                            }
                            catch (Exception tex){
                                totalRowChanged=-1;
                                logger.error(CommonHelper.getStackTraceString(tex));
                            }
                            JSONObject resultJsonObject = new JSONObject();
                            resultJsonObject.put("totalRowChanged",totalRowChanged);
                            responseData.put(requestFunctionName,resultJsonObject);
                            break;
                        }
                        case "machine_mode": {
                            responseData.put(requestFunctionName,DatabaseHelper.getMachineMode(connection,machine_id));
                            break;
                        }
                        case "motors_current_speed": {
                            responseData.put(requestFunctionName,DBCache.motorsCurrentSpeed);
                            break;
                        }
                        case "parameters_value": {
                            responseData.put(requestFunctionName,DatabaseHelper.getParameterValues(connection,machine_id));
                            break;
                        }
                        case "products_history": {
                            responseData.put(requestFunctionName,DatabaseHelper.getProductsHistory(connection,machine_id,requestFunction.getJSONObject("params")));
                            break;
                        }
                        case "statistics": {
                            responseData.put(requestFunctionName,DatabaseHelper.getStatisticsData(connection,machine_id,"statistics",requestFunction.getJSONObject("params")));
                            break;
                        }
                        case "statistics_hourly": {
                            responseData.put(requestFunctionName,DatabaseHelper.getStatisticsData(connection,machine_id,"statistics_hourly",requestFunction.getJSONObject("params")));
                            break;
                        }
                        case "statistics_minutely": {
                            responseData.put(requestFunctionName,DatabaseHelper.getStatisticsData(connection,machine_id,"statistics_minutely",requestFunction.getJSONObject("params")));
                            break;
                        }
                        case "statistics_counter": {
                            responseData.put(requestFunctionName,DatabaseHelper.getStatisticsData(connection,machine_id,"statistics_counter",requestFunction.getJSONObject("params")));
                            break;
                        }
                        case "statistics_bins": {
                            responseData.put(requestFunctionName,DatabaseHelper.getStatisticsData(connection,machine_id,"statistics_bins",requestFunction.getJSONObject("params")));
                            break;
                        }
                        case "statistics_bins_counter": {
                            responseData.put(requestFunctionName,DatabaseHelper.getStatisticsData(connection,machine_id,"statistics_bins_counter",requestFunction.getJSONObject("params")));
                            break;
                        }
                        case "statistics_bins_hourly": {
                            responseData.put(requestFunctionName,DatabaseHelper.getStatisticsData(connection,machine_id,"statistics_bins_hourly",requestFunction.getJSONObject("params")));
                            break;
                        }
                        case "statistics_oee": {
                            responseData.put(requestFunctionName,DatabaseHelper.getStatisticsData(connection,machine_id,"statistics_oee",requestFunction.getJSONObject("params")));
                            break;
                        }
                    }
                }
                connection.close();
                response.put("data",responseData);
                sendMessage(clientName,response.toString());
            }
            else {
                switch (request) {
                    case "basic_info": {
                        JSONObject basic_info = new JSONObject();
                        basic_info.put("alarmsInfo", DBCache.alarmsInfo);
                        basic_info.put("binsInfo", DBCache.binsInfo);
                        basic_info.put("boardsInfo", DBCache.boardsInfo);
                        basic_info.put("board_iosInfo", DBCache.board_iosInfo);
                        basic_info.put("conveyorsInfo", DBCache.conveyorsInfo);
                        basic_info.put("devicesInfo", DBCache.devicesInfo);
                        basic_info.put("inputsInfo", DBCache.inputsInfo);
                        basic_info.put("motorsInfo", DBCache.motorsInfo);
                        basic_info.put("parametersInfo", DBCache.parametersInfo);
                        basic_info.put("scsInfo", DBCache.scsInfo);
                        basic_info.put("machinesInfo", DBCache.machinesInfo);

                        JSONObject response = new JSONObject();
                        response.put("type", "basic_info");
                        response.put("basic_info", basic_info);
                        sendMessage(clientName, response.toString());
                        break;
                    }
                    case "getCommonStatus": {
                        int machineId = Integer.parseInt(jsonObject.get("machineId").toString());
                        JSONObject response = new JSONObject();
                        response.put("type", "getCommonStatus");
                        response.put("machineId", machineId);
                        response.put("disconnectedDeviceCounter", serverDBHandler.getDisconnectedDeviceCounter(machineId));
                        response.put("machineMode", serverDBHandler.getMachineMode(machineId));
                        response.put("activeAlarms", serverDBHandler.getActiveAlarms(machineId));
                        sendMessage(clientName, response.toString());
                        break;
                    }
                    case "getIoOutputStates": {
                        int machineId = Integer.parseInt(jsonObject.get("machineId").toString());
                        JSONObject params = (JSONObject) jsonObject.get("params");
                        JSONObject response = new JSONObject();
                        response.put("type", "getIoOutputStates");
                        response.put("machineId", machineId);
                        response.put("ioOutputStates", serverDBHandler.getIoOutputsStates(machineId));
                        sendMessage(clientName, response.toString());
                        break;
                    }
                    case "getStatistics": {
                        int machineId = Integer.parseInt(jsonObject.get("machineId").toString());
                        JSONObject params = (JSONObject) jsonObject.get("params");
                        long from_timestamp = Long.parseLong(params.get("from_timestamp").toString());
                        long to_timestamp = Long.parseLong(params.get("to_timestamp").toString());
                        JSONObject response = new JSONObject();
                        response.put("type", "getStatistics");
                        response.put("machineId", machineId);
                        response.put("statistics", serverDBHandler.getStatistics(machineId, from_timestamp, to_timestamp));
                        response.put("statistics_minute", serverDBHandler.getStatisticsMinutely(machineId, from_timestamp, to_timestamp));
                        sendMessage(clientName, response.toString());
                        break;
                    }
                    case "getStatisticsHourly": {
                        int machineId = Integer.parseInt(jsonObject.get("machineId").toString());
                        JSONObject params = (JSONObject) jsonObject.get("params");
                        long from_timestamp = Long.parseLong(params.get("from_timestamp").toString());
                        long to_timestamp = Long.parseLong(params.get("to_timestamp").toString());
                        JSONObject response = new JSONObject();
                        response.put("type", "getStatisticsHourly");
                        response.put("machineId", machineId);
                        response.put("statistics", serverDBHandler.getStatisticsHourly(machineId, from_timestamp, to_timestamp));
                        sendMessage(clientName, response.toString());
                        break;
                    }
                    case "getStatisticsMinutely": {
                        int machineId = Integer.parseInt(jsonObject.get("machineId").toString());
                        JSONObject params = (JSONObject) jsonObject.get("params");
                        long from_timestamp = Long.parseLong(params.get("from_timestamp").toString());
                        long to_timestamp = Long.parseLong(params.get("to_timestamp").toString());
                        JSONObject response = new JSONObject();
                        response.put("type", "getStatisticsMinutely");
                        response.put("machineId", machineId);
                        response.put("statistics", serverDBHandler.getStatisticsMinutely(machineId, from_timestamp, to_timestamp));
                        sendMessage(clientName, response.toString());
                        break;
                    }
                    case "getStatisticsCounter": {
                        int machineId = Integer.parseInt(jsonObject.get("machineId").toString());
                        JSONObject params = (JSONObject) jsonObject.get("params");
                        long from_timestamp = Long.parseLong(params.get("from_timestamp").toString());
                        long to_timestamp = Long.parseLong(params.get("to_timestamp").toString());
                        JSONObject response = new JSONObject();
                        response.put("type", "getStatisticsCounter");
                        response.put("machineId", machineId);
                        response.put("statistics", serverDBHandler.getStatisticsCounter(machineId, from_timestamp, to_timestamp));
                        sendMessage(clientName, response.toString());
                        break;
                    }
                    case "getStatisticsCounterLast": {
                        int machineId = Integer.parseInt(jsonObject.get("machineId").toString());
                        JSONObject params = (JSONObject) jsonObject.get("params");
                        JSONObject response = new JSONObject();
                        response.put("type", "getStatisticsCounterLast");
                        response.put("machineId", machineId);
                        response.put("statistics", serverDBHandler.getStatisticsCounterLast(machineId));
                        sendMessage(clientName, response.toString());
                        break;
                    }

                    case "getStatisticsBins": {
                        int machineId = Integer.parseInt(jsonObject.get("machineId").toString());
                        JSONObject params = (JSONObject) jsonObject.get("params");
                        long from_timestamp = Long.parseLong(params.get("from_timestamp").toString());
                        long to_timestamp = Long.parseLong(params.get("to_timestamp").toString());
                        JSONObject response = new JSONObject();
                        response.put("type", "getStatisticsBins");
                        response.put("machineId", machineId);
                        response.put("statistics", serverDBHandler.getStatisticsBins(machineId, from_timestamp, to_timestamp));
                        sendMessage(clientName, response.toString());
                        break;
                    }
                    case "getStatisticsBinsHourly": {
                        int machineId = Integer.parseInt(jsonObject.get("machineId").toString());
                        JSONObject params = (JSONObject) jsonObject.get("params");
                        long from_timestamp = Long.parseLong(params.get("from_timestamp").toString());
                        long to_timestamp = Long.parseLong(params.get("to_timestamp").toString());
                        JSONObject response = new JSONObject();
                        response.put("type", "getStatisticsBinsHourly");
                        response.put("machineId", machineId);
                        response.put("statistics", serverDBHandler.getStatisticsBinsHourly(machineId, from_timestamp, to_timestamp));
                        sendMessage(clientName, response.toString());
                        break;
                    }
                    case "getStatisticsBinsCounter": {
                        int machineId = Integer.parseInt(jsonObject.get("machineId").toString());
                        JSONObject params = (JSONObject) jsonObject.get("params");
                        long from_timestamp = Long.parseLong(params.get("from_timestamp").toString());
                        long to_timestamp = Long.parseLong(params.get("to_timestamp").toString());
                        JSONObject response = new JSONObject();
                        response.put("type", "getStatisticsBinsCounter");
                        response.put("machineId", machineId);
                        response.put("statistics", serverDBHandler.getStatisticsBinsCounter(machineId, from_timestamp, to_timestamp));
                        sendMessage(clientName, response.toString());
                        break;
                    }
                    case "getGeneralViewData": {
                        int machineId = Integer.parseInt(jsonObject.get("machineId").toString());
                        //JSONObject params= (JSONObject) jsonObject.get("params");
                        JSONObject response = new JSONObject();
                        response.put("type", "getGeneralViewData");
                        response.put("machineId", machineId);
                        response.put("binsStates", serverDBHandler.getBinsStates(machineId));
                        response.put("inputsStates", serverDBHandler.getInputsStates(machineId));
                        response.put("conveyorsStates", serverDBHandler.getConveyorsStates(machineId));
                        response.put("activeAlarms", serverDBHandler.getActiveAlarms(machineId));
                        response.put("statistics_counter", serverDBHandler.getStatisticsCounterLast(machineId));
                        response.put("statistics_oee", serverDBHandler.getStatisticsOeeLast(machineId));
                        sendMessage(clientName, response.toString());
                        break;
                    }
                    case "getGeneralDevicesViewData": {
                        int machineId = Integer.parseInt(jsonObject.get("machineId").toString());
                        //JSONObject params= (JSONObject) jsonObject.get("params");
                        JSONObject response = new JSONObject();
                        response.put("type", "getGeneralDevicesViewData");
                        response.put("machineId", machineId);
                        response.put("binsStates", serverDBHandler.getBinsStates(machineId));
                        response.put("inputsStates", serverDBHandler.getInputsStates(machineId));//for estops
                        response.put("conveyorsStates", serverDBHandler.getConveyorsStates(machineId));
                        response.put("devicesStates", serverDBHandler.getDevicesStates(machineId));
                        response.put("activeAlarms", serverDBHandler.getActiveAlarms(machineId));
                        response.put("statistics_counter", serverDBHandler.getStatisticsCounterLast(machineId));
                        response.put("statistics_oee", serverDBHandler.getStatisticsOeeLast(machineId));
                        sendMessage(clientName, response.toString());
                        break;
                    }
                    case "getGeneralMotorsViewData": {
                        int machineId = Integer.parseInt(jsonObject.get("machineId").toString());
                        //JSONObject params= (JSONObject) jsonObject.get("params");
                        JSONObject response = new JSONObject();
                        response.put("type", "getGeneralMotorsViewData");
                        response.put("machineId", machineId);
                        response.put("binsStates", serverDBHandler.getBinsStates(machineId));
                        response.put("inputsStates", serverDBHandler.getInputsStates(machineId));
                        response.put("conveyorsStates", serverDBHandler.getConveyorsStates(machineId));
                        response.put("devicesStates", serverDBHandler.getDevicesStates(machineId));
                        response.put("motorsCurrentSpeed", DBCache.motorsCurrentSpeed);
                        response.put("activeAlarms", serverDBHandler.getActiveAlarms(machineId));
                        response.put("machineMode", serverDBHandler.getMachineMode(machineId));
                        response.put("statistics_counter", serverDBHandler.getStatisticsCounterLast(machineId));
                        response.put("statistics_oee", serverDBHandler.getStatisticsOeeLast(machineId));
                        sendMessage(clientName, response.toString());
                        break;
                    }
                    case "getAlarmsViewData": {
                        int machineId = Integer.parseInt(jsonObject.get("machineId").toString());
                        //JSONObject params= (JSONObject) jsonObject.get("params");
                        JSONObject response = new JSONObject();
                        response.put("type", "getAlarmsViewData");
                        response.put("machineId", machineId);
                        response.put("activeAlarms", serverDBHandler.getActiveAlarms(machineId));
                        sendMessage(clientName, response.toString());
                        break;
                    }
                    case "getGeneralBinDetailsViewData": {
                        int machineId = Integer.parseInt(jsonObject.get("machineId").toString());
                        JSONObject params = (JSONObject) jsonObject.get("params");
                        int sort_manager_id = Integer.parseInt(params.get("sort_manager_id").toString());
                        JSONObject response = new JSONObject();
                        response.put("type", "getGeneralBinDetailsViewData");
                        response.put("machineId", machineId);
                        response.put("sort_manager_id", sort_manager_id);
                        response.put("inputsStates", serverDBHandler.getInputsStates(machineId));
                        response.put("binsStates", serverDBHandler.getBinsStates(machineId));
                        sendMessage(clientName, response.toString());
                        break;
                    }
                    case "sendDeviceCommand": {
                        int machineId = Integer.parseInt(jsonObject.get("machineId").toString());
                        JSONObject params = (JSONObject) jsonObject.get("params");
                        int deviceId = Integer.parseInt(params.get("deviceId").toString());
                        int command = Integer.parseInt(params.get("command").toString());
                        int parameter1 = Integer.parseInt(params.get("parameter1").toString());
                        //reset Command
                        if (deviceId == 86 && command == 0) {
                            try {
                                Connection dbConn = DataSource.getConnection();
                                Statement stmt = dbConn.createStatement();
                                String insertQuery = "INSERT IGNORE INTO statistics_counter (machine_id) SELECT DISTINCT machine_id FROM machines;" +
                                        "INSERT IGNORE INTO statistics_oee (machine_id) SELECT DISTINCT machine_id FROM machines;" +
                                        "INSERT IGNORE INTO statistics_bins_counter (machine_id,bin_id) SELECT DISTINCT machine_id,bin_id FROM bins;";
                                dbConn.setAutoCommit(false);
                                stmt.execute(insertQuery);
                                dbConn.commit();
                                dbConn.setAutoCommit(true);
                                stmt.close();
                                dbConn.close();
                            } catch (Exception e) {
                                logger.error(e.toString());
                            }
                        }

                        byte[] messageBytes = new byte[]{
                                0, 0, 0, 123, 0, 0, 0, 20,
                                (byte) (deviceId >> 24), (byte) (deviceId >> 16), (byte) (deviceId >> 8), (byte) (deviceId),
                                (byte) (command >> 24), (byte) (command >> 16), (byte) (command >> 8), (byte) (command),
                                (byte) (parameter1 >> 24), (byte) (parameter1 >> 16), (byte) (parameter1 >> 8), (byte) (parameter1)
                        };
                        Client client = cmClients.get(machineId);
                        client.sendBytes(messageBytes);
                        break;
                    }
                    case "getMaintViewData": {
                        int machineId = Integer.parseInt(jsonObject.get("machineId").toString());
                        //JSONObject params= (JSONObject) jsonObject.get("params");
                        JSONObject response = new JSONObject();
                        response.put("type", "getMaintViewData");
                        response.put("machineId", machineId);
                        response.put("ioInputsStates", serverDBHandler.getIoInputsStates(machineId));
                        response.put("ioOutputStates", serverDBHandler.getIoOutputsStates(machineId));
                        response.put("parameterValues", serverDBHandler.getParameterValues(machineId));
                        response.put("countersCurrentValue", DBCache.countersCurrentValue);
                        sendMessage(clientName, response.toString());
                        break;
                    }
                    case "sendSetParamMessage": {
                        int machineId = Integer.parseInt(jsonObject.get("machineId").toString());
                        JSONObject params = (JSONObject) jsonObject.get("params");
                        int paramId = Integer.parseInt(params.get("paramId").toString());
                        int value = Integer.parseInt(params.get("value").toString());
                        byte[] messageBytes = new byte[]{
                                0, 0, 0, 115, 0, 0, 0, 20, 0, 0, 0, 0,
                                (byte) (paramId >> 24), (byte) (paramId >> 16), (byte) (paramId >> 8), (byte) (paramId),
                                (byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8), (byte) (value)
                        };
                        Client client = cmClients.get(machineId);
                        client.sendBytes(messageBytes);
                        break;
                    }
                    case "getLoginUser": {
                        int machineId = Integer.parseInt(jsonObject.get("machineId").toString());
                        JSONObject params = (JSONObject) jsonObject.get("params");
                        String username = params.getString("username");
                        String password = params.getString("password");

                        JSONObject response = new JSONObject();
                        response.put("type", "getLoginUser");
                        response.put("machineId", machineId);
                        response.put("loginInfo", serverDBHandler.getLoginUser(username, password));
                        sendMessage(clientName, response.toString());
                        break;
                    }
                    case "changeCurrentUserPassword": {
                        int machineId = Integer.parseInt(jsonObject.get("machineId").toString());
                        JSONObject params = (JSONObject) jsonObject.get("params");
                        int id = Integer.parseInt(params.get("id").toString());
                        String password = params.get("password").toString();
                        String password_new = params.get("password_new").toString();
                        JSONObject response = new JSONObject();
                        response.put("type", "changeCurrentUserPassword");
                        response.put("machineId", machineId);
                        response.put("changeInfo", serverDBHandler.changeCurrentUserPassword(id, password, password_new));
                        sendMessage(clientName, response.toString());
                        break;
                    }
                    case "changeMode": {
                        int machineId = Integer.parseInt(jsonObject.get("machineId").toString());
                        JSONObject params = (JSONObject) jsonObject.get("params");
                        int mode = Integer.parseInt(params.get("mode").toString());
                        byte[] messageBytes = new byte[]{
                                0, 0, 0, 120, 0, 0, 0, 9, (byte) mode
                        };
                        Client client = cmClients.get(machineId);
                        client.sendBytes(messageBytes);
                        break;
                    }
                    case "getAlarmsHitList": {
                        int machineId = Integer.parseInt(jsonObject.get("machineId").toString());
                        JSONObject params = (JSONObject) jsonObject.get("params");
                        long from_timestamp = Long.parseLong(params.get("from_timestamp").toString());
                        long to_timestamp = Long.parseLong(params.get("to_timestamp").toString());
                        int page = 1;
                        int per_page = 2;
                        JSONObject response = new JSONObject();
                        response.put("type", "getAlarmsHitList");
                        response.put("machineId", machineId);
                        response.put("alarms", serverDBHandler.getAlarmsHitList(machineId, from_timestamp, to_timestamp));
                        sendMessage(clientName, response.toString());
                        break;
                    }
                    //--------------------------
                    case "mod_sort": {
                        int machineId = Integer.parseInt(jsonObject.get("id").toString());
                        int device_type = Integer.parseInt(jsonObject.get("device_type").toString());
                        int device_number = Integer.parseInt(jsonObject.get("device_number").toString());
                        //System.out.println("Machine:" + machineId + "D:" + device_type + " S:" + device_number);
                        JSONObject response = serverDBHandler.getModSort(machineId, device_type, device_number);
                        //System.out.println(response);
                        sendMessage(clientName, response.toString());
                        break;
                    }
                    case "induct": {
                        int machineId = Integer.parseInt(jsonObject.get("id").toString());
                        int induct_id = Integer.parseInt(jsonObject.get("induct_number").toString());
                        JSONObject response = serverDBHandler.getInduct(machineId, induct_id);
                        //System.out.println(response);
                        sendMessage(clientName, response.toString());
                        break;
                    }
                    case "status": {
                        int machineId = Integer.parseInt(jsonObject.get("id").toString());
                        JSONObject response = serverDBHandler.getErrorStatus(machineId);
                        sendMessage(clientName, response.toString());
                        break;
                    }
                    case "statistics": {
                        int machineId = Integer.parseInt(jsonObject.get("id").toString());
                        JSONObject response = serverDBHandler.getStatistics(machineId);
                        sendMessage(clientName, response.toString());
                        break;
                    }
                    case "sorted_graphs": {
                        int machineId = Integer.parseInt(jsonObject.get("id").toString());
                        JSONObject response = serverDBHandler.getSortedGraphs(machineId);
                        sendMessage(clientName, response.toString());
                        break;
                    }

                    case "package_list": {
                        int machineId = Integer.parseInt(jsonObject.get("id").toString());
                        JSONObject response = serverDBHandler.getPackageList(machineId);
                        sendMessage(clientName, response.toString());
                        break;
                    }
                    case "filtered_package_list": {
                        int machineId = Integer.parseInt(jsonObject.get("id").toString());
                        long startTimestamp = Long.parseLong(jsonObject.get("start").toString());
                        long endTimestamp = Long.parseLong(jsonObject.get("end").toString());
                        String sortingCode = jsonObject.get("sc").toString();
                        JSONObject response = serverDBHandler.getFilteredPackageList(machineId, startTimestamp, endTimestamp, sortingCode);
                        sendMessage(clientName, response.toString());
                        break;
                    }
                    case "filtered_package_to_sort_list": {
                        //int machineId = Integer.parseInt(jsonObject.get("id").toString());
                        String cartonId = jsonObject.get("cartonId").toString();
                        JSONObject response = serverDBHandler.getIngramProducts(cartonId);
                        sendMessage(clientName, response.toString());
                        break;
                    }
                    case "settings": {
                        int machineId = Integer.parseInt(jsonObject.get("id").toString());
                        JSONObject response = serverDBHandler.getSettings(machineId);
                        sendMessage(clientName, response.toString());
                        break;
                    }

                    case "change_induct": {
                        int machineId = Integer.parseInt(jsonObject.get("id").toString());
                        int mode = Integer.parseInt(jsonObject.get("mode").toString());
                        int inductId = 50 + Integer.parseInt(jsonObject.get("induct").toString());

                        Client client = cmClients.get(machineId);
                        try {
                            client.sendMessage(123, inductId, mode);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        break;
                    }
                    case "change_bin_mode": {
                        int machineId = Integer.parseInt(jsonObject.get("machine").toString());
                        int binId = Integer.parseInt(jsonObject.get("bin").toString());
                        int mode = Integer.parseInt(jsonObject.get("mode").toString());

                        //System.out.println("Change bin mode,  Machine: " + machineId + ", Bin : " + binId + ", Mode: " + mode);
                        Client client = cmClients.get(machineId);
                        try {
                            client.sendMessage(111, mode, binId);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        break;
                    }
                    case "device_command": {
                        int machineId = Integer.parseInt(jsonObject.get("id").toString());
                        int deviceId = Integer.parseInt(jsonObject.get("device").toString());
                        int operationId = Integer.parseInt(jsonObject.get("operation").toString());

                        Client client = cmClients.get(machineId);
                        try {
                            client.sendMessage(123, deviceId, operationId);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        break;
                    }

                }
            }
        }
        catch (Exception ex){
            logger.error(CommonHelper.getStackTraceString(ex));
        }
    }

    public void sendMessage(String clientName, String msg) {
        //msg = msg + ";#;#;";
        String startTag="<begin>";
        String endTag="</begin>";
        msg=startTag+msg+endTag;

        if((clientList.size() > 0) && (clientList.get(clientName) != null)) {
            SocketChannel sc = clientList.get(clientName);
            ByteBuffer buf = ByteBuffer.wrap(msg.getBytes());
            try {
                while (buf.hasRemaining()){
                    int n=sc.write(buf);
                    if(buf.remaining()>0){
                        logger.info("[DATA_SEND_TO_HMI]] waiting 30 for next send. MSG Len "+msg.length()+" Written bytes: " + n + ", Remaining: " + buf.remaining()+" MSG "+msg.substring(0,30));
                        Thread.sleep(30);
                    }
                }
            }
            catch (Exception e) {
                //e.printStackTrace();
                logger.error(e.toString());
            }
        } else {
            System.err.println("Client not found");
        }
    }

    public void addCmClients(int machineId, Client client) {
        if (!cmClients.containsKey(machineId)) {
            cmClients.put(machineId, client);
            //System.out.println("CM Client added to server");
        }
    }

    public void addListeners(ServerListener serverListener) {
        serverListeners.add(serverListener);
    }

    public void removeListener(ServerListener serverListener) {
        serverListeners.remove(serverListener);
    }

    public void notifyListeners(String fromName, String toName, String msg) {
        if(serverListeners.size() > 0) {
            serverListeners.forEach((el) -> el.update(fromName, toName, msg));
        }
    }

    public void addClientListListeners(ClientListListener clientListListener) {
        clientListListeners.add(clientListListener);

    }

    public void removeClientListListeners(ClientListListener clientListListener) {
        clientListListeners.remove(clientListListener);
    }

    public void notifyClientListListeners(String socketName, int opt) {
        if(clientListListeners.size() > 0) {
            clientListListeners.forEach((el) -> el.updateClientList(socketName, opt));
        }
    }
}
