/*
 **Copyright (C) 2017  xfalcon
 **
 **This program is free software: you can redistribute it and/or modify
 **it under the terms of the GNU General Public License as published by
 **the Free Software Foundation, either version 3 of the License, or
 **(at your option) any later version.
 **
 **This program is distributed in the hope that it will be useful,
 **but WITHOUT ANY WARRANTY; without even the implied warranty of
 **MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 **GNU General Public License for more details.
 **
 **You should have received a copy of the GNU General Public License
 **along with this program.  If not, see <http://www.gnu.org/licenses/>.
 **
 */

package com.github.xfalcon.vhosts.util;

import java.io.OutputStream;

public class FileUtils {


    public static boolean writeFile(OutputStream o, String content) throws Exception {
        o.write(content.getBytes());
        o.flush();
        o.close();
        return true;

    }

    public static String readFile(String filePath) throws Exception {
        java.io.File file = new java.io.File(filePath);
        java.io.FileInputStream fis = new java.io.FileInputStream(file);
        java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(fis));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            sb.append(line).append("\n");
        }
        br.close();
        fis.close();
        return sb.toString().trim();
    }

}