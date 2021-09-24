class A(object):
    b:B = None
    def create_B(self:"A") -> B:
        return B()
    def set_b(self:"A",b:B):
        self.b = b
class B(object):
    b:int = 2
    def set_b(self:"B",x:int):
        self.b = x
    def get_b(self:"B")->int:
        return self.b

a:A = None
a = A()
print(a.create_B().b)
a.set_b(a.create_B())
print(a.b.get_b())
a.b.set_b(5)
print(a.b.get_b())
