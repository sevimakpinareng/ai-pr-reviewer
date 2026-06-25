package com.example.prreviewer.service;

/** Thrown when a review could not produce any result (e.g. every file failed). */
public class ReviewFailedException extends RuntimeException {

    public ReviewFailedException(String message) {
        super(message);
    }
}
