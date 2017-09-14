import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;

public class SocketServerRunnable implements Runnable {

	private ServerSocket SocketServer;
	private Socket socket;
	private DataOutputStream dataOutputStream;
	private DataInputStream dataInputStream;
	private int socketPortNumber = 0;
	private long sessionEndTime = 0L;
	private long freshness = 0L;
	private Object lock = null;

	public SocketServerRunnable(Object lock) {
		this.lock = lock;
		socketPortNumber = Integer.parseInt(System.getProperty("socketPortNumber"));
		synchronized (Server.freshness) {
			this.freshness = Server.freshness.getTime();
		}
		try {
			SocketServer = new ServerSocket(socketPortNumber);
			socket = SocketServer.accept();
			dataInputStream = new DataInputStream(socket.getInputStream());
			dataOutputStream = new DataOutputStream(socket.getOutputStream());
		} catch (IOException e) {
			e.printStackTrace();
		}

	}

	@Override
	public void run() {
		while (true) {
			try {
				//System.out.println("Socket Runnable");
				while (freshness == Server.freshness.getTime()) {
					synchronized (lock) {
						//System.out.println("Waiting");
						lock.wait();
						//System.out.println("After wait");
					}
				}
				//System.out.println("No wait");
				//System.out.println("Freshness " + freshness);
				freshness = Server.freshness.getTime();
				dataOutputStream.writeLong(freshness);

				// } catch (InterruptedException e) {
			} catch (InterruptedException | IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
				// System.out.println("Problem in CDC extractor");
				System.exit(1);
			}

		}

	}

}
