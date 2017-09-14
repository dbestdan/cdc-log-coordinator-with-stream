import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.postgresql.PGConnection;
import org.postgresql.PGProperty;
import org.postgresql.replication.PGReplicationStream;

public class Server implements Config {
	public static Timestamp freshness = null;

	public static void main(String[] args) {
		long sessionEndTime = 0L;
		long sessionStartTime = 0L;
		PGReplicationStream stream = null;
		PGConnection replConnection = null;
		Connection conn = null;
		Properties props = null;
		String replicationSlot = null;
		String config = null;
		String tableGroup = null;
		List<String> tables = null;
		Connection sqlConnection = null;
		long cummulativeStaleness = 0L;
		long averageStaleness = 0L;
		long transactionId = 0L;
		long count = 0L;
		
		
		try {
			freshness = new Timestamp(System.currentTimeMillis());
			final Object lock = new Object();

			SocketServerRunnable serverSocket = new SocketServerRunnable(lock);
			new Thread(serverSocket).start();

			replicationSlot = System.getProperty("replicationSlot");
			config = System.getProperty("config");
			tableGroup = System.getProperty("tables");
			tables = new ArrayList<String>();
			if (tableGroup.equals("first")) {
				tables = table_group_1;
			} else if (tableGroup.equals("second")) {
				tables = table_group_2;
			} else {
				tables = table_group_3;
			}

			Properties propFromFile = new Properties();
			propFromFile.load(new FileInputStream(System.getProperty("prop")));

			// initialize replication connection
			String user = propFromFile.getProperty("user");
			String password = propFromFile.getProperty("password");
			String url = propFromFile.getProperty("url");

			props = new Properties();
			PGProperty.USER.set(props, user);
			PGProperty.PASSWORD.set(props, password);
			PGProperty.ASSUME_MIN_SERVER_VERSION.set(props, "9.4");
			PGProperty.REPLICATION.set(props, "database");
			PGProperty.PREFER_QUERY_MODE.set(props, "simple");

			conn = DriverManager.getConnection(url, props);
			replConnection = conn.unwrap(PGConnection.class);
			
			
			

			stream = replConnection.getReplicationAPI().replicationStream().logical()
					.withSlotName(replicationSlot).withSlotOption("include-xids", true)
					.withSlotOption("include-timestamp", "on").withSlotOption("skip-empty-xacts", true)
					.withStatusInterval(1, TimeUnit.SECONDS).start();

			// get sqlConnection
			sqlConnection = getConnection();

			
			sessionStartTime = System.currentTimeMillis();

			String stalenessFilename = "staleness_" + config + "_" + dateFormat.format(new Date());
			String dataFilename = "chunk_" + config + "_" + dateFormat.format(new Date());

			Writer stalenessOutPut = new BufferedWriter(
					new OutputStreamWriter(new FileOutputStream(stalenessFilename, true), "UTF-8"));
			Writer dataWriter = new BufferedWriter(
					new OutputStreamWriter(new FileOutputStream(dataFilename, true), "UTF-8"));

			int noOfSet = Integer.parseInt(propFromFile.getProperty("no_of_set"));
			int numberOfExperiment = Integer.parseInt(propFromFile.getProperty("no_of_experiment"));

			for (int l = 0; l < noOfSet; l++) {

				for (int i = 1; i <= numberOfExperiment; i++) {
					String experiment = propFromFile.getProperty("experiment_" + i);
					String experimentParameter[] = experiment.split("_");
					long sleepDuration = Long.parseLong(experimentParameter[1]);
					long runDuration = Long.parseLong(experimentParameter[3]) * 60000L;
					sessionEndTime = System.currentTimeMillis() + runDuration;

					while (sessionEndTime > System.currentTimeMillis()) {
						//Thread.sleep(2);

						ByteBuffer msg = stream.readPending();

						if (msg == null) {
							TimeUnit.MILLISECONDS.sleep(10L);
							continue;
						}

						int offset = msg.arrayOffset();
						byte[] source = msg.array();
						int length = source.length - offset;
						// convert byte buffer into string
						String data = new String(source, offset, length);

						// then convert it into bufferedreader
						BufferedReader reader = new BufferedReader(new StringReader(data));
						String line = reader.readLine();

						while (line != null) {
							boolean isTableFound = false;
							//System.out.println(line);

							if (line.contains("BEGIN") || line.contains("COMMIT")) {
								if (line.contains("BEGIN")) {
									transactionId = Long.parseLong(line.split(" ")[1]);
									//System.out.println("TransactionId: " + transactionId);

								} else {
									ResultSet rs = sqlConnection.createStatement().executeQuery(
											"select pg_xact_commit_timestamp('" + transactionId + "'::xid)");
									rs.next();
									freshness = rs.getTimestamp(1);
									synchronized (lock) {
										lock.notifyAll();
									}

									//System.out.println("Freshness: " + freshness);

									long staleness = System.currentTimeMillis() - freshness.getTime();
									count++;
									cummulativeStaleness += staleness;
									averageStaleness = cummulativeStaleness / count;

									stalenessOutPut.append((System.currentTimeMillis() - sessionStartTime) + ","
											+ staleness + "," + averageStaleness + "\n");
									//System.out.println(
									//		"Staleness:" + staleness + " AvgStaleness:" + averageStaleness + "\n");
									stalenessOutPut.flush();

								}
							} else {

								for (String tableName : tables) {
									if (line.contains(tableName)) {
										dataWriter.append(line);
										break;
									}
								}
								dataWriter.flush();

							}
							line = reader.readLine();
						}
						// feedback
						stream.setAppliedLSN(stream.getLastReceiveLSN());
						stream.setFlushedLSN(stream.getLastReceiveLSN());

					}

				}

			}
	
			System.exit(0);
		} catch (SQLException | InterruptedException | NumberFormatException |IOException e) {
						
			long errorRecordedTime = (sessionEndTime - System.currentTimeMillis())/60000;
			System.out.println("Error recoreded time: "+ errorRecordedTime);
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

	}

	public static Connection getConnection() {
		Properties prop = new Properties();
		try {
			prop.load(new FileInputStream(System.getProperty("prop")));
		} catch (IOException e1) {
			e1.printStackTrace();
		}

		String user = prop.getProperty("user");
		String password = prop.getProperty("password");
		String url = prop.getProperty("url");

		Connection conn = null;
		Properties connectionProps = new Properties();
		connectionProps.put("user", user);
		connectionProps.put("password", password);
		try {
			Class.forName("org.postgresql.Driver");
			conn = DriverManager.getConnection(url, connectionProps);
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
		} catch (SQLException e) {
			e.printStackTrace();
		}
		return conn;
	}

}
