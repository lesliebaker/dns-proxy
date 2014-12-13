package server;

import java.io.*;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.AbstractMap.SimpleEntry;
import java.util.Date;
import java.util.HashMap;

public class HttpRequest {
	/** Help variables */
	final static String CRLF = "\r\n";
	final static int HTTP_PORT = 80;
	/** Store the request parameters */
	String method;
	String URI;
	String version;
	String headers = "";
	/** Server and port */
	private String host;
	private InetAddress hostAddress;
	private int port;
	
	/** keep track of cookie from DNS */
	private Object currentCookie;
	/** Lock for DNS interactions */
	//private ReentrantLock lock;
	/** keep track of hostname-ip mapping to add to proxy cache */
	private SimpleEntry<String, InetAddress> entry;
	/** keep track of time needed to make this request */
	private long time;

	/** Create HttpRequest by reading it from the client socket 
	 * @throws BadMethodException 
	 * @throws DNSException */
	public HttpRequest(BufferedReader from, Object currentCookie, HashMap<String, InetAddress> cache,
			boolean useDNS) throws BadMethodException, EmptyRequestException, 
												BadRequestException, BadVersionException, DNSException {
		time = new Date().getTime();
		this.currentCookie = currentCookie;
		//lock = new ReentrantLock();
		String firstLine = "";
		try {
			firstLine = from.readLine();
		} catch (IOException e) {
			System.out.println("Error reading request line: " + e);
		}
		
		// For testing purposes... I don't know why I kept getting empty requests but I
		// don't want to crash on them.
		// It only happens when I connect my whole network to the proxy. Probably there are
		// just requests floating around.
		if (firstLine == null)
			throw new EmptyRequestException();
		
		String[] tmp = firstLine.split(" ");
		if (tmp.length == 3) {
			method = tmp[0];
			URI = tmp[1];
			version = tmp[2];
		}
		else
			throw new BadRequestException();

		System.out.println("URI is: " + URI);

		if (!method.equals("GET")) {
			System.out.println("Error: Method not GET");
			throw new BadMethodException();
		}
		if (!version.equals("HTTP/1.0"))
			throw new BadVersionException();
		try {
			String line = from.readLine();
			while (line.length() != 0) {
				
				// check for improperly formatted headers
				if (line.indexOf(":") <= 0 || line.indexOf(":") >= line.length() - 1)
					throw new BadRequestException();

				// throw away Connection (we're gonna make it close anyways)
				if (line.startsWith("Connection:")) {
					line = from.readLine();
					continue;
				}
				
				headers += line + CRLF;
				/* We need to find host header to know which server to
				 * contact in case the request URI is not complete. */
				if (line.startsWith("Host:")) {
					tmp = line.split(" ");
					if (tmp[1].indexOf(':') > 0) {
						String[] tmp2 = tmp[1].split(":");
						host = tmp2[0];
						port = Integer.parseInt(tmp2[1]);
					} else {
						host = tmp[1];
						port = HTTP_PORT;
					}
				}
				line = from.readLine();
			}
			
			// Make sure we actually have the host. If it wasn't in a header, try to get it
			// from the URI. If it's not in the URI, it will throw an exception.
			if (host == null) {
				host = getHostFromURI(URI);
				port = getPortFromURI(URI);
				URI = getURLFromURI(URI);
			}
			// If the URI was absolute, get the relative bit to send to the host.
			if (URI.indexOf("http://") > -1 || URI.indexOf("https://") > -1)
				URI = getURLFromURI(URI);
			
		} catch (IOException e) {
			//System.out.println("Error reading from socket: " + e);
			return;
		}
		if (useDNS) {
			if (cache.get(host) == null) {
				while (hostAddress == null)
					hostAddress = handleDNS(host);
				entry = new SimpleEntry<String, InetAddress>(host, hostAddress);
			} else {
				hostAddress = cache.get(host);
			}
			System.out.println("Host to contact is: " + host + "/" + hostAddress.getHostAddress() +
					" at port " + port);
		}
		System.out.println("Host to contact is: " + host + " at port " + port);
		//else
		//	throw new DNSException(host, hostAddress);
		
		// update time needed to make this request
		time = new Date().getTime() - time;
	}
	
	public InetAddress handleDNS(String host) throws DNSException {
		try {
			InetAddress address = InetAddress.getByName("localhost");
			Socket connection = new Socket(address, 1053);
			
			ObjectOutputStream toDNS = new ObjectOutputStream(connection.getOutputStream());
			ObjectInputStream fromDNS = new ObjectInputStream(connection.getInputStream());
			
			// If we don't have a cookie, start protocol to get one
			if (currentCookie == null) {
				//if (lock.isHeldByCurrentThread())
				//	lock.unlock();
				System.out.println("Requesting cookie...");
				// initial message
				toDNS.writeObject("I want to talk");
				
				// receive cookie
				@SuppressWarnings("unused")
				int length = (int)fromDNS.readObject();
				Object cookie = fromDNS.readObject();
				currentCookie = cookie;
				//System.out.println("cookie: " + cookie.toString());
			}
			System.out.println("Using cookie...");
			// Send DNS request + cookie
			toDNS.writeObject(currentCookie);
			toDNS.writeObject(host);
			
			// receive DNS response (if authentication worked)
			String hostAddress = (String)fromDNS.readObject();
			System.out.println("host address: " + hostAddress);
			InetAddress ip = InetAddress.getByName(hostAddress);
			if (hostAddress == null) {
				//lock.lock();
				currentCookie = null;
				connection.close();
				//throw new DNSException("Error with cookie");
				return null;
			}
			
			connection.close();
			
			return ip;
		} catch (UnknownHostException e) {
			System.out.println("Error connecting to DNS");
		} catch (IOException e) {
			System.out.println("Socket error:");
			e.printStackTrace();
		} catch (ClassNotFoundException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
		return null;
	}
	
	/** Return cookie from client */
	public Object getCookie() {
		return currentCookie;
	}

	/** Return host for which this request is intended */
	public InetAddress getHost() {
		if (hostAddress == null)
			try {
				return InetAddress.getByName(host);
			} catch (UnknownHostException e) {
				System.out.println("ERROR: Unknown host: " + host);
				return null;
			}
		else
			return hostAddress;
	}
	
	/** Return entry for cache */
	public SimpleEntry<String, InetAddress> getEntry() {
		return entry;
	}
	
	/** Return time for this request */
	public long getTime() {
		return time;
	}

	/** Return port for server */
	public int getPort() {
		return port;
	}
	
	/** Return URI for request */
	public String getURI() {
		return URI;
	}

	/**
	 * Convert request into a string for easy re-sending.
	 */
	public String toString() {
		String req = "";

		req = method + " " + URI + " " + version + CRLF;
		req += headers;
		// This proxy does not support persistent connections 
		req += "Connection: close" + CRLF;
		req += CRLF;

		return req;
	}
	
	/**
	 * Get host from full URI
	 * @throws BadRequestException 
	 */
	private String getHostFromURI(String uri) throws BadRequestException {
		int startIndex = uri.indexOf("://") + 3;
		
		// there was no http://
		if (startIndex == 2)
			throw new BadRequestException();
		
		// Get the index of either right before the port or right before the / after the host
		int stopIndex = uri.indexOf(":", startIndex);
		if (stopIndex == -1)
			stopIndex = uri.indexOf("/", startIndex);
		if (stopIndex == -1)
			stopIndex = uri.length() - 1;
		
		// There is still the possibility that there was nothing after http://
		// if so, it's a bad request format
		try {
			return uri.substring(startIndex, stopIndex);
		} catch (StringIndexOutOfBoundsException e) {
			throw new BadRequestException();
		}
	}
	
	/**
	 * Get port from full URI
	 */
	private int getPortFromURI(String uri) {
		int from = uri.indexOf("://") + 3;
		
		// Get the index of the colon before the port (if there is one)
		int startIndex = uri.indexOf(":", from) + 1;
		
		// Return default if port is not specified
		if (startIndex == 0)
			return HTTP_PORT;
		
		// Get the index of the end of the port (if there is one)
		int stopIndex = uri.indexOf("/", from);
		
		// If there isn't a slash, just go to the end of the uri
		if (stopIndex == -1)
			stopIndex = uri.length();
		
		// One last check - really the exception should never happen
		try {
			return Integer.parseInt(uri.substring(startIndex, stopIndex));
		}
		catch (NumberFormatException e) {
			return HTTP_PORT;
		}
	}
	
	/**
	 * Get relative URL from absolute URI
	 */
	private String getURLFromURI(String uri) {
		int index = uri.indexOf("://") + 3;
		return uri.substring(uri.indexOf("/", index));
	}
	
	/**
	 * Exception for bad method (not GET)
	 */
	public class BadMethodException extends Exception {
		private static final long serialVersionUID = 1L;

		public BadMethodException() {
		}
	}
	
	/**
	 * Exception for empty requests
	 */
	public class EmptyRequestException extends Exception {
		private static final long serialVersionUID = 1L;

		public EmptyRequestException() {
		}
	}
	
	/**
	 * Exception for bad (incorrectly formatted) requests
	 */
	public class BadRequestException extends Exception {
		private static final long serialVersionUID = 1L;

		public BadRequestException() {
		}
	}
	
	/**
	 * Exception for bad HTTP version (not 1.0)
	 */
	public class BadVersionException extends Exception {
		private static final long serialVersionUID = 1L;

		public BadVersionException() {
		}
	}

	/**
	 * Exception for error in DNS protocol resulting in not receiving the host IP
	 */
	public class DNSException extends Exception {
		private static final long serialVersionUID = 1L;
		private String message;

		public DNSException(String host, InetAddress hostAddress) {
			this.message = "Error finding IP for host: " + host + " (received " + hostAddress + ")";
		}
		
		public DNSException(String message) {
			this.message = message;
		}
		
		public String message() {
			return message;
		}
	}
}