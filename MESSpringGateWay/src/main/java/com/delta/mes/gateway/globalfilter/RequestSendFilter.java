package com.delta.mes.gateway.globalfilter;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import javax.annotation.Resource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;

import com.delta.mes.gateway.service.APIAuthService;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class RequestSendFilter implements GlobalFilter {

	private static final Logger log = LoggerFactory.getLogger(AuthorizeGatewayGlobalFilter.class);
	private static final String AUTHORIZE_TOKEN = "tokenID";
	private static final APIAuthService service = new APIAuthService(log);
	@Resource
	private DiscoveryClient discoveryClient;

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		String contentType;
		ServerHttpRequest req = exchange.getRequest();
		ServerHttpResponse rep = exchange.getResponse();
		String path = req.getURI().getPath();
		URI requestUri = req.getURI();
		String method = req.getMethod().name();
		String schema = requestUri.getScheme();
		if ((!"http".equals(schema) && !"https".equals(schema))) {
			return chain.filter(exchange);
		}
		if (method.equals("GET")) {

		}

		return chain.filter(exchange);
        

	}

	/**
	 * 获取请求体中的字符串内容
	 * 
	 * @param serverHttpRequest
	 * @return
	 */
	private String resolveBodyFromRequest(ServerHttpRequest serverHttpRequest) {
		// 获取请求体
		Flux<DataBuffer> body = serverHttpRequest.getBody();
		StringBuilder sb = new StringBuilder();

		body.subscribe(buffer -> {
			byte[] bytes = new byte[buffer.readableByteCount()];
			buffer.read(bytes);
			DataBufferUtils.release(buffer);
			String bodyString = new String(bytes, StandardCharsets.UTF_8);
			sb.append(bodyString);
		});
		return sb.toString();

	}
}
