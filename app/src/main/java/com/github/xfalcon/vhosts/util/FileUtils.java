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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class FileUtils {

    // 用 try-with-resources 确保异常时也关闭流（不泄露文件句柄）；UTF-8 与仓储写入统一。
    public static boolean writeFile(OutputStream o, String content) throws IOException {
        try (OutputStream out = o) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
        return true;
    }
}
