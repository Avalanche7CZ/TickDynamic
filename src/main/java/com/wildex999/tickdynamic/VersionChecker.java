package com.wildex999.tickdynamic;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraftforge.common.ForgeVersion;

public class VersionChecker implements Runnable {
	public class VersionData {
		public boolean checkOk = false;
		public String mcVersion;
		public String modVersion;
		public String updateUrl;
	}
	AtomicBoolean checkDone;
	VersionData data;
	public VersionChecker() {
		checkDone = new AtomicBoolean();
		checkDone.set(false);
	}
	@Override
	public void run() {
		data = new VersionData();
		data.checkOk = false;
		String encoding = "UTF-8";
		String url = "http://mods.stjerncraft.com:8080";
		String query = "v=error";
		BufferedReader in = null;
		try {
			query = String.format("mv=%s&v=%s&sv=%s", URLEncoder.encode("forge1.7.10", encoding), 
				URLEncoder.encode(TickDynamicMod.VERSION, encoding), URLEncoder.encode(ForgeVersion.getVersion(), encoding));
			URLConnection connection = new URL(url + "/?" + query).openConnection();
			connection.setRequestProperty("Accept-Charset", encoding);
			connection.setRequestProperty("Host", "mods.stjerncraft.com");
			// Avoid blocking server thread if endpoint is slow
			try {
				connection.setConnectTimeout(5000);
				connection.setReadTimeout(5000);
			} catch(Throwable ignored) {}
			in = new BufferedReader(new InputStreamReader(connection.getInputStream(), encoding));
			String response = in.readLine();
			if(response == null) { checkDone.set(true); return; }
			String[] args = response.split(",");
			if(args.length != 4) { data.checkOk = false; checkDone.set(true); return; }
			data.checkOk = args[0].equals("ok");
			data.mcVersion = args[1];
			data.modVersion = args[2];
			data.updateUrl = args[3];
		} catch (Exception e) {
			if(TickDynamicMod.debug) e.printStackTrace();
		} finally {
			try { if(in != null) in.close(); } catch(Exception ignore) {}
		}
		checkDone.set(true);
	}
	public void runVersionCheck() {
		checkDone.set(false);
		new Thread(this, "TD-VersionChecker").start();
	}
	public VersionData getVersionData() {
		if(!checkDone.get()) return null;
		return data;
	}
}
