package com.ias.finance;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class FeeExecutionProjectionSpringContextTest {
 @Test void productionConstructorIsSelectedByARealSpringContext(){
  try(var context=new AnnotationConfigApplicationContext()){
   context.registerBean(DataSource.class,()->mock(DataSource.class));
   context.registerBean(FinanceAccess.class,()->mock(FinanceAccess.class));
   context.register(FeeExecutionProjectionController.class);
   context.refresh();
   assertNotNull(context.getBean(FeeExecutionProjectionController.class));
  }
 }
}
