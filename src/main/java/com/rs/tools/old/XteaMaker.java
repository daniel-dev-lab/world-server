// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <http://www.gnu.org/licenses/>.
//
//  Copyright (C) 2021 Trenton Kress
//  This file is part of project: Darkan
//
package com.rs.tools.old;

import java.io.*;

public class XteaMaker {

	@SuppressWarnings("resource")
	public static void main(String[] args) {
		try {
			BufferedReader stream = new BufferedReader(new InputStreamReader(new FileInputStream("xtea650.txt")));
			while (true) {
				String line = stream.readLine();
				if (line == null)
					break;
				if (line.startsWith("--"))
					continue;
				String[] spaceSplitLine = line.split(" ");
				int regionId = Integer.valueOf(spaceSplitLine[0]);
				String[] xteaSplit = spaceSplitLine[3].split("\\.");
				/*
				 * for(byte c : spaceSplitLine[3].getBytes()) {
				 * System.out.println(c); System.out.println((char) c); }
				 */

				if (xteaSplit[0].equals("0") && xteaSplit[1].equals("0") && xteaSplit[2].equals("0") && xteaSplit[3].equals("0"))
					continue;
				BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream("convertedXtea/" + regionId + ".txt")));
				for (String xtea : xteaSplit) {
					writer.append(xtea);
					writer.newLine();
					writer.flush();
				}
			}
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}
