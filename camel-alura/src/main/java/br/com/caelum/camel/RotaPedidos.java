package br.com.caelum.camel;

import org.apache.activemq.camel.component.ActiveMQComponent;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;

public class RotaPedidos {

	public static void main(String[] args) throws Exception {

		CamelContext context = new DefaultCamelContext();
		context.addComponent("activemq", ActiveMQComponent.activeMQComponent("tcp://localhost:61616"));
		context.addRoutes(new RouteBuilder() {
			@Override
			public void configure() throws Exception {
				errorHandler(deadLetterChannel("activemq:queue:pedidos.DLQ")
						.useOriginalMessage()
						.logExhaustedMessageHistory(true)
						.maximumRedeliveries(3)
						.redeliverDelay(5000)
						.onRedelivery(new Processor() {
							@Override
							public void process(Exchange exchange) throws Exception {
								int couter = (int)exchange.getIn().getHeader(Exchange.REDELIVERY_COUNTER);
								int max = (int)exchange.getIn().getHeader(Exchange.REDELIVERY_MAX_COUNTER);
								System.out.println("Redelivery"+ couter + "/" + max);
							}
				}));
				
				//from("file:pedidos?delay=5s&noop=true").
				from("activemq:queue:pedidos").
				log("${file:name}").
				routeId("rota-pedidos").
				delay(1000).
				to("validator:pedido.xsd").
					multicast().
						parallelProcessing().
							timeout(500).
								to("seda:soap").
								to("seda:http");
				
				from("seda:soap").
					routeId("rota-soap").
				to("xslt:pedido-para-soap.xslt").
					log("Resultado do template: ${body}").
					setHeader(Exchange.CONTENT_TYPE, constant("text/xml")).
				to("http4://localhost:8080/webservices/financeiro");
				
				from("seda:http").
					routeId("rota-http").
					setProperty("pedidoId",xpath("/pedido/id/text()")).
					setProperty("email",xpath("/pedido/pagamento/email-titular/text()")).
				split().
					xpath("/pedido/itens/item").
				filter().
					xpath("item/formato[text()='EBOOK']").
				setProperty("ebookId",xpath("/item/livro/codigo/text()")).
				setHeader(Exchange.HTTP_QUERY,
						simple("clienteId=${property.email}&pedidoId=${property.pedidoId}&ebookId=${property.ebookId}")).
				to("http4://localhost:8080/webservices/ebook/item");
			}
		});
		
		context.start();
		Thread.sleep(20000);
		context.stop();

	}	
}
