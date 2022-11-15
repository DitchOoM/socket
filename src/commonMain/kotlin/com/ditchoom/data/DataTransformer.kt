package com.ditchoom.data

interface DataTransformer<I, O> {
    suspend fun transform(input: I): O
}
