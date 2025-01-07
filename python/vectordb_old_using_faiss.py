import numpy as np
import faiss
import os

index_file_path = "faiss_index.idx"

# TRY FIXING THE OMP ISSUE AND REMOVE THIS LINE
os.environ["KMP_DUPLICATE_LIB_OK"]="TRUE"

class VectorDatabase:
    def __init__(self, embedding_dim):
        self.embedding_dim = embedding_dim
        self.embeddings = []  # Store embeddings as a list
        self.metadata = []  # Store associated strings as a list

        # Initialize FAISS index for fast nearest neighbor search
        if index_file_path and os.path.exists(index_file_path):
            self.load_index(index_file_path)
        else:
            self.index = faiss.IndexFlatL2(embedding_dim)

    def save_embedding(self, embedding, associated_string):
        """
        Save an embedding with an associated string into the database.

        :param embedding: np.ndarray, shape=(embedding_dim,)
        :param associated_string: str
        """
        if not isinstance(embedding, np.ndarray):
            embedding = np.array(embedding)
        if embedding.shape != (self.embedding_dim,):
            raise ValueError(f"Embedding must have shape ({self.embedding_dim},).")

        self.embeddings.append(embedding)
        self.metadata.append(associated_string)

        # Add the embedding to the FAISS index
        self.index.add(np.array([embedding], dtype=np.float32))
        faiss.write_index(self.index, index_file_path)

    def search(self, query_embedding, top_k=1):
        """
        Search for the closest embedding in the database to the given query embedding.

        :param query_embedding: np.ndarray, shape=(embedding_dim,)
        :param top_k: int, number of nearest neighbors to retrieve (default is 1)
        :return: List of tuples [(associated_string, confidence_score), ...]
        """
        if not isinstance(query_embedding, np.ndarray):
            query_embedding = np.array(query_embedding)
        if query_embedding.shape != (self.embedding_dim,):
            raise ValueError(f"Query embedding must have shape ({self.embedding_dim},).")
        
        # Perform the search in FAISS
        distances, indices = self.index.search(np.array([query_embedding], dtype=np.float32), top_k)

        results = []
        for dist, idx in zip(distances[0], indices[0]):
            print(str(dist)+" "+str(idx))
            if idx == -1:  # No valid result found
                continue
            confidence_score = 1 / (1 + np.sqrt(dist))  # Convert L2 distance to a confidence score
            results.append((self.metadata[idx], confidence_score))

        # Sort results by confidence score in descending order
        results.sort(key=lambda x: x[1], reverse=True)

        return results

    def load_index(self, index_file_path):
        """
        Load the FAISS index from disk.

        :param index_file_path: str, path to the file from which the index will be loaded
        """
        self.index = faiss.read_index(index_file_path)


# Example usage
if __name__ == "__main__":
    embedding_dim = 128
    db = VectorDatabase(embedding_dim)

    # Example embeddings and strings
    embedding1 = np.random.rand(embedding_dim).astype(np.float32)
    embedding2 = np.random.rand(embedding_dim).astype(np.float32)

    db.save_embedding(embedding1, "First embedding")
    db.save_embedding(embedding2, "Second embedding")

    # Query the database
    query_embedding = embedding1 + 0.01  # Slightly perturbed version of embedding1
    results = db.search(query_embedding)

    print("Search results:")
    for associated_string, confidence_score in results:
        print(f"String: {associated_string}, Confidence: {confidence_score:.4f}")
