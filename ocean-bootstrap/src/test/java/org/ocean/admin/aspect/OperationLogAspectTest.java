package org.ocean.admin.aspect;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.ocean.admin.kernel.audit.CurrentOperator;
import org.ocean.admin.kernel.audit.OperationLog;
import org.ocean.admin.kernel.audit.OperationLogCommand;
import org.ocean.admin.kernel.audit.OperationType;
import org.ocean.admin.kernel.common.ResponseResult;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OperationLogAspectTest {

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void shouldTreatBusinessErrorResponseAsFailedOperation() throws Throwable {
        OperationLogSerializer serializer = mock(OperationLogSerializer.class);
        OperationLogDispatcher dispatcher = mock(OperationLogDispatcher.class);
        OperationLogAspect aspect = new OperationLogAspect(serializer, dispatcher);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        Method method = SampleController.class.getDeclaredMethod("update", String.class);
        OperationLog annotation = method.getAnnotation(OperationLog.class);

        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getArgs()).thenReturn(new Object[]{"42"});
        when(joinPoint.proceed()).thenReturn(ResponseResult.error(409, "状态冲突"));
        when(signature.getDeclaringTypeName()).thenReturn(SampleController.class.getName());
        when(signature.getName()).thenReturn("update");
        when(signature.getParameterNames()).thenReturn(new String[]{"id"});
        when(serializer.serializeRequest(new String[]{"id"}, new Object[]{"42"}, new String[0]))
                .thenReturn("{\"id\":\"42\"}");

        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/sample/42");
        request.setRemoteAddr("127.0.0.1");
        request.setAttribute(CurrentOperator.REQUEST_ATTRIBUTE, new CurrentOperator(7L, "admin"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        Object result = aspect.record(joinPoint, annotation);

        assertThat(result).isInstanceOf(ResponseResult.class);
        ArgumentCaptor<OperationLogCommand> captor = ArgumentCaptor.forClass(OperationLogCommand.class);
        verify(dispatcher).dispatch(captor.capture(), org.mockito.ArgumentMatchers.eq(true));
        OperationLogCommand command = captor.getValue();
        assertThat(command.success()).isFalse();
        assertThat(command.errorMessage()).isEqualTo("状态冲突");
        assertThat(command.userId()).isEqualTo(7L);
        assertThat(command.username()).isEqualTo("admin");
        assertThat(command.requestUrl()).isEqualTo("/api/sample/42");
    }

    static class SampleController {

        @OperationLog(module = "SAMPLE", type = OperationType.UPDATE, description = "更新样例")
        public void update(String id) {
        }
    }
}
