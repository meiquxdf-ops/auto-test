package com.hjmicro.domain;


import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Map;

@AllArgsConstructor
@Data
public class Result<T>{

    private boolean success;

    private String errorMessage;

    private T data;

    private Map otherInfo;


    public static <T> Result of(T t){
        return new Result(Boolean.TRUE,"",t,null);
    }
    public static <T> Result<T> error(String errorMessage){
        return new Result<>(Boolean.FALSE, errorMessage, null, null);
    }

}
