/*
 * ConnectionHandler.java
 * Oct 7, 2012
 *
 * Simple Web Server (SWS) for CSSE 477
 * 
 * Copyright (C) 2012 Chandan Raj Rupakheti
 * 
 * This program is free software: you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public License 
 * as published by the Free Software Foundation, either 
 * version 3 of the License, or any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/lgpl.html>.
 * 
 */
 
package server;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.text.SimpleDateFormat;

import protocol.HttpRequest;
import protocol.HttpResponse;
import protocol.HttpResponseFactory;
import protocol.Protocol;
import protocol.ProtocolException;

/**
 * This class is responsible for handling a incoming request
 * by creating a {@link HttpRequest} object and sending the appropriate
 * response be creating a {@link HttpResponse} object. It implements
 * {@link Runnable} to be used in multi-threaded environment.
 * 
 * @author Chandan R. Rupakheti (rupakhet@rose-hulman.edu)
 */
public class ConnectionHandler implements Runnable {
	private Server server;
	private Socket socket;
	
	public ConnectionHandler(Server server, Socket socket) {
		this.server = server;
		this.socket = socket;
	}
	
	/**
	 * @return the socket
	 */
	public Socket getSocket() {
		return socket;
	}

	/**
	 * The entry point for connection handler. It first parses
	 * incoming request and creates a {@link HttpRequest} object,
	 * then it creates an appropriate {@link HttpResponse} object
	 * and sends the response back to the client (web browser).
	 */
	public void run() {
		// Get the start time
		long start = System.currentTimeMillis();
		
		InputStream inStream = null;
		OutputStream outStream = null;
		
		try {
			inStream = this.socket.getInputStream();
			outStream = this.socket.getOutputStream();
		}
		catch(Exception e) {
			// Cannot do anything if we have exception reading input or output stream
			// May be have text to log this for further analysis?
			e.printStackTrace();
			
			// Increment number of connections by 1
			server.incrementConnections(1);
			// Get the end time
			long end = System.currentTimeMillis();
			this.server.incrementServiceTime(end-start);
			return;
		}
		
		// At this point we have the input and output stream of the socket
		// Now lets create a HttpRequest object
		HttpRequest request = null;
		HttpResponse response = null;
		try {
			int buffSize = socket.getReceiveBufferSize();
			request = HttpRequest.read(inStream, buffSize);
		}
		catch(ProtocolException pe) {
			// We have some sort of protocol exception. Get its status code and create response
			// We know only two kind of exception is possible inside fromInputStream
			// Protocol.BAD_REQUEST_CODE and Protocol.NOT_SUPPORTED_CODE
			int status = pe.getStatus();
			if(status == Protocol.BAD_REQUEST_CODE) {
				response = HttpResponseFactory.create400BadRequest(Protocol.CLOSE);
			}
			if(status == Protocol.NOT_SUPPORTED_CODE) {
				response = HttpResponseFactory.create505NotSupported(Protocol.CLOSE);
			}
		}
		catch(Exception e) {
			e.printStackTrace();
			// For any other error, we will create bad request response as well
			response = HttpResponseFactory.create400BadRequest(Protocol.CLOSE);
		}
		if(response != null) {
			// Means there was an error, now write the response object to the socket
			try {
				response.write(outStream);
			}
			catch(Exception e){
				// We will ignore this exception
				e.printStackTrace();
			}

			// Increment number of connections by 1
			server.incrementConnections(1);
			// Get the end time
			long end = System.currentTimeMillis();
			this.server.incrementServiceTime(end-start);
			return;
		}
		
		// We reached here means no error so far, so lets process further
		try {
			if(!request.getVersion().equalsIgnoreCase(Protocol.VERSION)) {
				response = HttpResponseFactory.create505NotSupported(Protocol.CLOSE);
			}
			else if(request.getMethod().equalsIgnoreCase(Protocol.GET)) {
				String uri = request.getUri();				
				File file = getFile(uri);
				if (file == null) {
					response = HttpResponseFactory.create404NotFound(Protocol.CLOSE);
				}	
				else {
					response = HttpResponseFactory.create200OK(file, Protocol.CLOSE);
				}	
			}
			else if(request.getMethod().equalsIgnoreCase(Protocol.POST)){
				String uri = request.getUri();
				File file = new File(server.getRootDirectory() + uri);
				if (!file.exists()){
					response = HttpResponseFactory.create404NotFound(Protocol.CLOSE);
				}
				else {
					SimpleDateFormat sdf = new SimpleDateFormat("MM-dd-yyy_HH-mm-ss");
					String timestamp = sdf.format(start);
					// I currently have the body being added to an HTML file, this should be changed
					// but doing html is friendly for a browser demonstration and testing
					String postPath = file.getPath() + File.separator + timestamp + ".html";
					File postFile = new File(postPath);
					
					// Create a new file that is a subordinate at the given URI
					postFile.createNewFile();
					PrintWriter writer = new PrintWriter(postPath, "UTF-8");
					writer.print(request.getBody());
					writer.close();
					
					String postFileURL = uri + File.separator + timestamp + ".html";
					response = HttpResponseFactory.create301PermanentlyMoved(postFileURL, Protocol.CLOSE);	
				}
			}
			else if(request.getMethod().equalsIgnoreCase(Protocol.PUT)){
				String uri = request.getUri();
				File file = getFile(uri);
				if (file == null){
					response = HttpResponseFactory.create404NotFound(Protocol.CLOSE);
				}
				else {
					PrintWriter writer = new PrintWriter(file.getPath(), "UTF-8");
					writer.print(request.getBody());
					writer.close();
					
					response = HttpResponseFactory.create200OK(file, Protocol.CLOSE);
				}	
			}
			else if(request.getMethod().equalsIgnoreCase(Protocol.DELETE)){
				String uri = request.getUri();
				File file = getFile(uri);
				if (getFile(uri) == null){
					response = HttpResponseFactory.create404NotFound(Protocol.CLOSE);
				}
				else {
					response = HttpResponseFactory.create200OK(file, Protocol.CLOSE);
					response.setFile(null);
					file.delete();
				}	
			}
			else {
				response = HttpResponseFactory.create400BadRequest(Protocol.CLOSE);
			}
		}
		catch(Exception e) {
			e.printStackTrace();
		}
		
		try{
			// Write response and we are all done so close the socket
			response.write(outStream);
			socket.close();
		}
		catch(Exception e){
			// We will ignore this exception
			e.printStackTrace();
		} 
		
		System.out.println("Request");
		System.out.println(request);
		System.out.println("Response");
		System.out.println(response);
		
		// Increment number of connections by 1
		server.incrementConnections(1);
		// Get the end time
		long end = System.currentTimeMillis();
		this.server.incrementServiceTime(end-start);
	}
	
	private File getFile(String uri) {
		String rootDirectory = server.getRootDirectory();
		// Combine them together to form absolute file path
		File file = new File(rootDirectory + uri);
		// Check if the file exists
		if(file.exists()) {
			if(file.isDirectory()) {
				// Look for default index.html file in a directory
				String location = rootDirectory + uri + System.getProperty("file.separator") + Protocol.DEFAULT_FILE;
				file = new File(location);
				return file.exists()? file: null;
			}else { 
				// Its a file
				return file;
			}
		} else {
			return null;
		}
	}
}