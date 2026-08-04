package com.jlshell.program.api;

import com.google.gson.JsonElement;

/** 由宿主使用当前账号令牌代发的同源 API 请求。 */
public record AccountRequest(String method, String path, JsonElement body) {
}
