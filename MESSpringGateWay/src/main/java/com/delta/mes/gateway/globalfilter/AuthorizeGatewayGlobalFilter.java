package com.delta.mes.gateway.globalfilter;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Resource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ServerWebExchange;

import com.alibaba.fastjson.JSON;
import com.delta.mes.base.MESBaseController;
import com.delta.mes.function.entity.system.FunctionPojo;
import com.delta.mes.gateway.entity.URL;
import com.delta.mes.gateway.service.APIAuthService;
import com.delta.mes.util.JsonUtil;
import com.delta.mes.util.StringUtil;

import io.netty.buffer.ByteBufAllocator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
/**
 * 身份驗證 globalfilter
 * 
 * @author YONGHUI.ZHI
 *
 */

@Configuration
public class AuthorizeGatewayGlobalFilter implements GlobalFilter {

	private static final Logger log = LoggerFactory.getLogger(AuthorizeGatewayGlobalFilter.class);
	private static final APIAuthService service = new APIAuthService(log);
	@Resource
	private DiscoveryClient disClient;
	@Resource
	private LoadBalancerClient loadBlance;

	@Value("${mes.url}")
	private String mesUrl;

	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		String contentType;
		ServiceInstance serviceInstance = null;
		boolean formdataType = false;
		String postBody = "";
		String ip = "";
		String tokenID = "";
		ServerHttpRequest req = exchange.getRequest();
		ServerHttpResponse rep = exchange.getResponse();
		String path = req.getURI().getPath();
		int port = req.getURI().getPort();
		URI requestUri = req.getURI();
		String method = req.getMethod().name();
		String schema = requestUri.getScheme();
		if ((!"http".equals(schema) && !"https".equals(schema))) {
			return chain.filter(exchange);
		}
		if (method.equals("GET") && port == 10101) {
			return chain.filter(exchange);
		} else if (method.equals("POST")) {
			AccessRecord accessRecord = new AccessRecord();
			accessRecord.setPath(requestUri.getPath());
			accessRecord.setQueryString(req.getQueryParams());
			exchange.getAttributes().put("startTime", System.currentTimeMillis());
			ip = exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
			try {
				postBody = resolveBodyFromRequest(req);
				boolean authRight = true;
				FunctionPojo auth = JsonUtil.getBeanFromJson(postBody, FunctionPojo.class);
				try {
					authRight = MESBaseController.isUserAuthorityValidate(auth);
				} catch (IndexOutOfBoundsException e) {
					// 沒配置第三方 不驗證
						rep.setStatusCode(HttpStatus.FORBIDDEN);
						return rep.setComplete();
				}
				if (authRight) {

					contentType = exchange.getRequest().getHeaders().getFirst("Content-Type");
					if (!StringUtil.isEmpty(contentType)) {
						formdataType = contentType.startsWith("multipart/form-data");
					}
					// 獲取postbody
					if (!formdataType) {
						// 二次封裝
						URL uri = service.getURI(auth.getFunCode());
						// 判断是发往原mes还是接口
						String url = null;
						System.out.println(1);
						if (uri != null) {
							try {
								serviceInstance = loadBlance.choose(uri.getApplicationName());
							} catch (Exception e) {
								e.printStackTrace();
							}
							url = schema + "://" + serviceInstance.getHost() + ":" + serviceInstance.getPort()
									+ uri.getUrl();

						} else {
							url = schema + "://" + mesUrl + requestUri.getPath();
						}
						System.out.println(url);
						// postBody = exchange.getAttribute("cachedRequestBodyObject");
						// 下面将请求体再次封装写回到 request 里,传到下一级.
						URI eTx = URI.create(url);
						ServerHttpRequest newRequest = req.mutate().uri(eTx).build();
						DataBuffer bodyDataBuffer = stringBuffer(postBody);
						Flux<DataBuffer> bodyFlux = Flux.just(bodyDataBuffer);
						newRequest = new ServerHttpRequestDecorator(newRequest) {
							@Override
							public Flux<DataBuffer> getBody() {
								return bodyFlux;
							}
						};
						accessRecord.setBody(formatStr(postBody));
						ServerWebExchange newExchange = exchange.mutate().request(newRequest).build();
						exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR,
								newRequest.getURI());
						exchange = newExchange;

					}

					return returnMono(chain, exchange, accessRecord);
				} else {
					rep.setStatusCode(HttpStatus.FORBIDDEN);
					return rep.setComplete();
				}

			} catch (Exception e) {
				log.error("IP:{},URI:{},TokenID:{},Exception:{}", ip, path, tokenID, e);
				rep.setStatusCode(HttpStatus.FORBIDDEN);
				return rep.setComplete();
			}
		}
		return chain.filter(exchange);
	}


	private Mono<Void> returnMono(GatewayFilterChain chain, ServerWebExchange exchange, AccessRecord accessRecord) {
		return chain.filter(exchange).then(Mono.fromRunnable(() -> {
			Long startTime = exchange.getAttribute("startTime");
			if (startTime != null) {
				long executeTime = (System.currentTimeMillis() - startTime);
				accessRecord.setExpendTime(executeTime);
				accessRecord.setHttpCode(Objects.requireNonNull(exchange.getResponse().getStatusCode()).value());
				writeAccessLog(JSON.toJSONString(accessRecord) + "\r\n");
			}
		}));
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

	/**
	 * 去掉空格,换行和制表符
	 * 
	 * @param str
	 * @return
	 */
	private String formatStr(String str) {
		if (str != null && str.length() > 0) {
			Pattern p = Pattern.compile("\\s*|\t|\r|\n");
			Matcher m = p.matcher(str);
			return m.replaceAll("");
		}
		return str;
	}

	private DataBuffer stringBuffer(String value) {
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		NettyDataBufferFactory nettyDataBufferFactory = new NettyDataBufferFactory(ByteBufAllocator.DEFAULT);
		DataBuffer buffer = nettyDataBufferFactory.allocateBuffer(bytes.length);
		buffer.write(bytes);
		return buffer;
	}

	/**
	 * 访问记录对象
	 */
	private class AccessRecord {
		private String path;
		private String body;
		private MultiValueMap<String, String> queryString;
		private long expendTime;
		private int httpCode;

		public String getPath() {
			return path;
		}

		public void setPath(String path) {
			this.path = path;
		}

		public String getBody() {
			return body;
		}

		public void setBody(String body) {
			this.body = body;
		}

		public MultiValueMap<String, String> getQueryString() {
			return queryString;
		}

		public void setQueryString(MultiValueMap<String, String> queryString) {
			this.queryString = queryString;
		}

		public long getExpendTime() {
			return expendTime;
		}

		public void setExpendTime(long expendTime) {
			this.expendTime = expendTime;
		}

		public int getHttpCode() {
			return httpCode;
		}

		public void setHttpCode(int httpCode) {
			this.httpCode = httpCode;
		}
	}

	private void writeAccessLog(String str) {
		File file = new File("access.log");
		if (!file.exists()) {
			try {
				if (file.createNewFile()) {
					file.setWritable(true);
				}
			} catch (IOException e) {
				log.error("创建访问日志文件失败.{}", e.getMessage(), e);
			}
		}

		try (FileWriter fileWriter = new FileWriter(file.getName(), true)) {
			fileWriter.write(str);
		} catch (IOException e) {
			log.error("写访问日志到文件失败. {}", e.getMessage(), e);
		}

	}

}

